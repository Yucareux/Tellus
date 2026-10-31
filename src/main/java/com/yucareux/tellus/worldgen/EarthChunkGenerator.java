package com.yucareux.tellus.worldgen;

import com.mojang.datafixers.util.Pair;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.integration.distant_horizons.LodSnowSlope;
import com.yucareux.tellus.preload.TerrainPreloadPackage;
import com.yucareux.tellus.preload.TerrainPreloadPackageRegistry;
import com.yucareux.tellus.world.data.canopy.TellusCanopyHeightSource;
import com.yucareux.tellus.world.data.cover.LandCoverTransition;
import com.yucareux.tellus.world.data.cover.TellusLandCoverSource;
import com.yucareux.tellus.world.data.elevation.TellusElevationSource;
import com.yucareux.tellus.world.data.koppen.TellusKoppenSource;
import com.yucareux.tellus.world.data.mask.TellusLandMaskSource;
import com.yucareux.tellus.world.data.osm.OsmPerf;
import com.yucareux.tellus.world.data.osm.OsmQueryMode;
import com.yucareux.tellus.world.data.osm.BridgeSupportLayout;
import com.yucareux.tellus.world.data.osm.OsmBuildingFeature;
import com.yucareux.tellus.world.data.osm.OsmStreetLightFeature;
import com.yucareux.tellus.world.data.osm.RoadAreaFeature;
import com.yucareux.tellus.world.data.osm.RoadClass;
import com.yucareux.tellus.world.data.osm.RoadFeature;
import com.yucareux.tellus.world.data.osm.RoadMode;
import com.yucareux.tellus.world.data.osm.RoadPointKind;
import com.yucareux.tellus.world.data.osm.RoadSurfaceStyle;
import com.yucareux.tellus.world.data.osm.TellusOsmBuildingSource;
import com.yucareux.tellus.world.data.osm.TellusOsmInfrastructureSource;
import com.yucareux.tellus.world.data.osm.TellusOsmRoadSource;
import com.yucareux.tellus.world.data.osm.TellusOsmSandSource;
import com.yucareux.tellus.world.data.resolve.ResolveEcoregion;
import com.yucareux.tellus.world.data.resolve.TellusResolveSource;
import com.yucareux.tellus.world.realtime.TellusRealtimeState;
import com.yucareux.tellus.worldgen.arnis.ArnisBuildingRules;
import com.yucareux.tellus.worldgen.road.StreetLightPlanner;
import com.yucareux.tellus.worldgen.road.StreetLightModel;
import com.yucareux.tellus.worldgen.building.BuildingBlueprint;
import com.yucareux.tellus.worldgen.building.BuildingEntranceAccess;
import com.yucareux.tellus.worldgen.building.BuildingEntranceLayout;
import com.yucareux.tellus.worldgen.building.BuildingFloorPlan;
import com.yucareux.tellus.worldgen.building.TellusBuildingArchitecture;
import com.yucareux.tellus.worldgen.building.TellusBuildingInteriors;
import com.yucareux.tellus.worldgen.building.BuildingProfile;
import com.yucareux.tellus.worldgen.building.BuildingStyle;
import com.yucareux.tellus.worldgen.building.TellusBuildingBlueprints;
import com.yucareux.tellus.worldgen.building.TellusBuildingFacade;
import com.yucareux.tellus.worldgen.building.TellusBuildingMaterials;
import com.yucareux.tellus.worldgen.building.BuildingPlacementSupport;
import com.yucareux.tellus.worldgen.building.TellusBuildingProfiles;
import com.yucareux.tellus.worldgen.landmark.LandmarkPlacement;
import com.yucareux.tellus.worldgen.landmark.TellusLandmarks;
import com.yucareux.tellus.worldgen.caves.TellusCaveDepthMapper;
import com.yucareux.tellus.worldgen.caves.TellusNoiseSettingsAdapter;
import com.yucareux.tellus.worldgen.caves.TellusVanillaCarverRunner;
import com.yucareux.tellus.worldgen.tree.TellusProceduralTreeGenerator;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Direction;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.Holder.Reference;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.core.HolderSet.Named;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.BiomeGenerationSettings.PlainBuilder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.level.levelgen.blending.Blender;
import com.yucareux.tellus.worldgen.WorldgenApi.TreeFeatureHandle;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.StructureSet.StructureSelectionEntry;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

public final class EarthChunkGenerator extends TellusChunkGeneratorBridge {
   private static final TellusElevationSource ELEVATION_SOURCE = TellusWorldgenSources.elevation();
   private static final TellusCanopyHeightSource CANOPY_HEIGHT_SOURCE = TellusWorldgenSources.canopyHeight();
   private static final TellusResolveSource RESOLVE_SOURCE = TellusResolveSource.shared();
   private static final TellusLandCoverSource LAND_COVER_SOURCE = TellusWorldgenSources.landCover();
   private static final TellusKoppenSource KOPPEN_SOURCE = TellusWorldgenSources.koppen();
   private static final TellusLandMaskSource LAND_MASK_SOURCE = TellusWorldgenSources.landMask();
   private static final TellusOsmRoadSource OSM_ROAD_SOURCE = TellusWorldgenSources.osmRoads();
   private static final TellusOsmInfrastructureSource OSM_INFRASTRUCTURE_SOURCE = TellusWorldgenSources.osmInfrastructure();
   private static final TellusOsmBuildingSource OSM_BUILDING_SOURCE = TellusWorldgenSources.osmBuildings();
   private static final TellusOsmSandSource OSM_SAND_SOURCE = TellusWorldgenSources.osmSand();
   private static final double ESA_WORLD_COVER_RESOLUTION_METERS = 10.0;
   private static final int ESA_NO_DATA = 0;
   private static final int ESA_BUILT_UP = 50;
   private static final int ESA_WATER = 80;
   private static final int ESA_MANGROVES = 95;
   private static final int RANDOM_BIOME_TREE_CHANCE = 35;
   private static final int PROCEDURAL_TREE_CELL_SIZE = TellusProceduralTreeGenerator.PLACEMENT_CELL_SIZE;
   private static final long RANDOM_BIOME_TREE_SALT = -7163147898164839021L;
   private static final int SPAGHETTI_WATER_GUARD_DEPTH = 4;
   private static final double OCEAN_CHUNK_CARVER_GUARD_RATIO = 0.7;
   private static final int OCEAN_CARVER_FLOOR_BUFFER = 3;
   private static final float AXOLOTL_CHUNK_CHANCE = 0.55F;
   private static final float AXOLOTL_POND_CHANCE = 0.68F;
   private static final int MAX_AXOLOTLS_PER_CHUNK = 2;
   private static final int OSM_ROAD_MAX_SCALE = 15;
   private static final int OSM_ROAD_QUERY_MARGIN = 64;
   private static final int OSM_BUILDING_MAX_SCALE = 15;
   private static final int OSM_BUILDING_QUERY_MARGIN = 8;
   private static final int OSM_BUILDING_BASE_Y_OFFSET = intProperty("tellus.osm.buildings.baseYOffset", -1, -8, 8);
   private static final int VILLAGE_WATER_SAMPLE_MARGIN = intProperty("tellus.structures.villageWaterSampleMargin", 2, 0, 32);
   private static final int VILLAGE_WATER_SAMPLE_STEP = intProperty("tellus.structures.villageWaterSampleStep", 8, 1, 32);
   private static final int OSM_ROAD_CLASS_SEPARATION = 0;
   private static final int OSM_ROAD_BRIDGE_LEVEL_HEIGHT = intProperty("tellus.osm.roads.bridgeLevelHeight", 3, 1, 16);
   private static final int OSM_ROAD_BRIDGE_MAX_RISE = intProperty("tellus.osm.roads.bridgeMaxRise", 10, 1, 64);
   private static final int OSM_ROAD_BRIDGE_RAMP_HORIZONTAL_PER_VERTICAL = intProperty("tellus.osm.roads.bridgeRampHorizontalPerVertical", 4, 1, 32);
   private static final int OSM_TUNNEL_SIDE_CLEARANCE = 3;
   private static final int OSM_TUNNEL_INTERNAL_HEIGHT = 7;
   private static final int OSM_ROAD_SURFACE_CLEARANCE = 3;
   private static final int OCEAN_MONUMENT_SAMPLE_STEP = 8;
   private static final int OCEAN_MONUMENT_MARGIN = 8;
   private static final int OCEAN_MONUMENT_CORE_INSET = 8;
   private static final int OCEAN_MONUMENT_MIN_DEEP_OCEAN_DEPTH = 12;
   private static final double OCEAN_MONUMENT_MIN_OCEAN_RATIO = 0.85;
   private static final double OCEAN_MONUMENT_MIN_DEEP_OCEAN_RATIO = 0.7;
   private static final int OCEAN_MONUMENT_TOP_BELOW_SEA = 2;
   private static final int OSM_CROSSING_QUERY_MARGIN = 32;
   private static final BlockState ROAD_MAIN_STATE = TellusBlockReferences.concreteState("GRAY");
   private static final BlockState ROAD_PAVED_LIGHT_STATE = TellusBlockReferences.concreteState("LIGHT_GRAY");
   private static final BlockState ROAD_PAVED_SMOOTH_STATE = Blocks.SMOOTH_STONE.defaultBlockState();
   private static final BlockState ROAD_PEDESTRIAN_STATE = Blocks.SMOOTH_STONE.defaultBlockState();
   private static final BlockState ROAD_GRAVEL_STATE = Blocks.GRAVEL.defaultBlockState();

   private static final BlockState ROAD_NORMAL_STATE = Blocks.SMOOTH_STONE.defaultBlockState();

   private static final BlockState ROAD_DIRT_STATE = Blocks.DIRT_PATH.defaultBlockState();
   private static final BlockState ROAD_COBBLESTONE_STATE = Blocks.COBBLESTONE.defaultBlockState();
   private static final BlockState ROAD_STONE_PAVERS_STATE = Blocks.STONE_BRICKS.defaultBlockState();
   private static final BlockState ROAD_BRICK_STATE = Blocks.BRICKS.defaultBlockState();
   private static final BlockState ROAD_SAND_STATE = Blocks.SANDSTONE.defaultBlockState();
   private static final BlockState ROAD_WOOD_STATE = Blocks.OAK_PLANKS.defaultBlockState();
   private static final BlockState ROAD_CONCRETE_STATE = TellusBlockReferences.concreteState("LIGHT_GRAY");
   private static final BlockState ROAD_MARKING_STATE = TellusBlockReferences.concreteState("WHITE");
   private static final BlockState BRIDGE_SUPPORT_SHAFT_STATE = Blocks.QUARTZ_PILLAR.defaultBlockState();
   private static final BlockState BRIDGE_SUPPORT_CAP_STATE = Blocks.QUARTZ_BRICKS.defaultBlockState();
   private static final BlockState ROAD_POINT_BASE_STATE = Blocks.STONE_BRICK_WALL.defaultBlockState();
   private static final BlockState ROAD_SIGNAL_POLE_STATE = Blocks.IRON_BARS.defaultBlockState();
   private static final BlockState ROAD_SIGNAL_HEAD_STATE = TellusBlockReferences.woolState("BLACK");
   private static final BlockState ROAD_SIGNAL_RED_STATE = TellusBlockReferences.concreteState("RED");
   private static final BlockState ROAD_SIGNAL_YELLOW_STATE = TellusBlockReferences.concreteState("YELLOW");
   private static final BlockState ROAD_SIGNAL_GREEN_STATE = TellusBlockReferences.concreteState("LIME");
   private static final BlockState ROAD_BUS_STOP_SIGN_STATE = TellusBlockReferences.woolState("BLUE");
   private static final BlockState ROAD_BUS_STOP_PANEL_STATE = TellusBlockReferences.woolState("WHITE");
   private static final BlockState ROAD_WASTE_BIN_STATE = Blocks.CAULDRON.defaultBlockState();
   private static final BlockState ROAD_RECYCLING_BIN_STATE = TellusBlockReferences.concreteState("GREEN");
   private static final BlockState ROAD_BICYCLE_RACK_STATE = Blocks.IRON_BARS.defaultBlockState();
   private static final BlockState ROAD_FOUNTAIN_STATE = Blocks.WATER_CAULDRON.defaultBlockState();
   private static final BlockState ROAD_BOLLARD_STATE = Blocks.COBBLESTONE_WALL.defaultBlockState();
   private static final int BUILDING_SLICE_PADDING = 6;
   private static final boolean FULL_CHUNK_OSM_NON_BLOCKING = Boolean.parseBoolean(System.getProperty("tellus.osm.fullChunksNonBlocking", "false"));
   private static final boolean FAST_FULL_CHUNK = Boolean.parseBoolean(System.getProperty("tellus.chunkgen.fastFullChunk", "true"));
   private static final boolean NON_BLOCKING_TERRAIN_INPUTS = Boolean.parseBoolean(System.getProperty("tellus.chunkgen.nonBlockingTerrainInputs", "false"));
   private static final boolean MEMORY_ONLY_TERRAIN_CRITICAL_PATH = Boolean.parseBoolean(
      System.getProperty("tellus.chunkgen.memoryOnlyTerrainCriticalPath", "false")
   );
   private static final boolean DEFER_TERRAIN_REFINEMENT = Boolean.parseBoolean(System.getProperty("tellus.chunkgen.deferTerrainRefinement", "false"));
   private static final boolean BLOCKING_TERRAIN_INPUT_WARMUP = Boolean.parseBoolean(System.getProperty("tellus.prefetch.blockingWarmup", "false"));
   private static final EarthChunkGenerator.SurfaceMode FULL_CHUNK_SURFACE_MODE = surfaceModeProperty(
      "tellus.chunkgen.surfaceMode", EarthChunkGenerator.SurfaceMode.TWO_TIER
   );
   private static final int HEIGHT_GRID_CACHE_ENTRIES = intProperty("tellus.chunkgen.heightGrid.cacheEntries", 512, 0, 8192);
   private static final boolean CHUNK_DETAIL_DEFER_ROADS = Boolean.parseBoolean(System.getProperty("tellus.chunkdetail.deferRoads", "false"));
   private static final boolean CHUNK_DETAIL_DEFER_BUILDINGS = Boolean.parseBoolean(System.getProperty("tellus.chunkdetail.deferBuildings", "false"));
   private static final boolean CHUNK_DETAIL_DEFER_DETAILED_WATER = Boolean.parseBoolean(System.getProperty("tellus.chunkdetail.deferDetailedWater", "false"));
   private static final boolean CHUNK_DETAIL_DEFER_TREES = Boolean.parseBoolean(System.getProperty("tellus.chunkdetail.deferTrees", "false"));
   private static final boolean CHUNK_DETAIL_LEGACY_BLOCKING = Boolean.parseBoolean(System.getProperty("tellus.chunkdetail.legacyBlocking", "true"));
   private static final int CHUNK_DETAIL_PREFETCH_RADIUS = intProperty("tellus.chunkdetail.prefetchRadius", 1, 0, 32);
   private static final int CHUNK_DETAIL_APPLY_BUDGET_PER_TICK = intProperty("tellus.chunkdetail.applyBudgetPerTick", 2, 0, 64);
   private static final int PREPARED_CHUNK_STATE_REAP_INTERVAL_TICKS = intProperty(
      "tellus.chunkgen.preparedChunkStateReapIntervalTicks", 200, 20, 72000
   );
   private static final int PREPARED_CHUNK_STATE_STALE_TICKS = intProperty(
      "tellus.chunkgen.preparedChunkStateStaleTicks", 6000, PREPARED_CHUNK_STATE_REAP_INTERVAL_TICKS, 144000
   );
   private static final long PREPARED_CHUNK_STATE_STALE_NANOS = TimeUnit.MILLISECONDS.toNanos((long)PREPARED_CHUNK_STATE_STALE_TICKS * 50L);
   private static final int TERRAIN_REFINEMENT_APPLY_BUDGET_PER_TICK = intProperty(
      "tellus.chunkgen.terrainRefinementApplyBudgetPerTick", 4, 0, 64
   );
   private static final int SPAWN_EXACT_CHUNK_RADIUS = intProperty("tellus.chunkgen.spawnExactChunkRadius", 2, 0, 16);
   private static final int MOVEMENT_PREFETCH_FORWARD_EXTRA = intProperty("tellus.prefetch.movement.forwardExtra", 2, 0, 32);
   private static final int MOVEMENT_PREFETCH_SIDE_EXTRA = intProperty("tellus.prefetch.movement.sideExtra", 1, 0, 32);
   private static final int MOVEMENT_PREFETCH_TELEPORT_BURST_RADIUS = intProperty("tellus.prefetch.movement.teleportBurstRadius", 5, 0, 64);
   private static final int MOVEMENT_PREFETCH_TELEPORT_THRESHOLD_CHUNKS = intProperty("tellus.prefetch.movement.teleportThresholdChunks", 3, 1, 64);
   private static final int MOVEMENT_PREFETCH_DIRECTION_CHANGE_MIN_DEGREES = intProperty("tellus.prefetch.movement.directionChangeDegrees", 60, 1, 180);
   private static final int MOVEMENT_PREFETCH_DIRECTION_CHANGE_COOLDOWN_TICKS = intProperty(
      "tellus.prefetch.movement.directionChangeCooldownTicks", 8, 0, 200
   );
   private static final int MOVEMENT_PREFETCH_EDGE_TRIGGER_DISTANCE_BLOCKS = intProperty(
      "tellus.prefetch.movement.edgeTriggerDistanceBlocks", 6, 0, 16
   );
   private static final int MOVEMENT_PREFETCH_MIN_SPEED_MILLIBLOCKS_PER_TICK = intProperty(
      "tellus.prefetch.movement.minSpeedMilliBlocksPerTick", 50, 0, 5000
   );
   private static final double MOVEMENT_PREFETCH_DIRECTION_CHANGE_MAX_DOT = Math.cos(
      Math.toRadians(MOVEMENT_PREFETCH_DIRECTION_CHANGE_MIN_DEGREES)
   );
   private static final double MOVEMENT_PREFETCH_MIN_SPEED_BLOCKS_PER_TICK = MOVEMENT_PREFETCH_MIN_SPEED_MILLIBLOCKS_PER_TICK / 1000.0;
   private static final double MOVEMENT_PREFETCH_MIN_SPEED_SQ =
      MOVEMENT_PREFETCH_MIN_SPEED_BLOCKS_PER_TICK * MOVEMENT_PREFETCH_MIN_SPEED_BLOCKS_PER_TICK;
   private static final int LOD_SHORELINE_DENSE_CACHE_MAX_AREA = intProperty("tellus.dhLodShorelineDenseCacheMaxArea", 100000, 4096, 1048576);
   private static final int LOD_MOUNTAIN_TRANSITION_DENSE_CACHE_MAX_AREA = intProperty(
      "tellus.dhLodMountainTransitionDenseCacheMaxArea", 131072, 4096, 1048576
   );
   private static final int MOUNTAIN_SNOW_SOURCE_CACHE_ENTRIES = intProperty(
      "tellus.chunkgen.mountainSnowSourceCacheEntries", 32768, 0, 1048576
   );
   private static final BlockState[] BADLANDS_BANDS = new BlockState[]{
      Blocks.TERRACOTTA.defaultBlockState(),
      TellusBlockReferences.terracottaState("ORANGE"),
      TellusBlockReferences.terracottaState("YELLOW"),
      TellusBlockReferences.terracottaState("BROWN"),
      TellusBlockReferences.terracottaState("RED"),
      TellusBlockReferences.terracottaState("LIGHT_GRAY"),
      TellusBlockReferences.terracottaState("WHITE")
   };
   private static final int CHUNK_SIDE = 16;
   private static final int CHUNK_MASK = 15;
   private static final int CHUNK_AREA = 256;
   private static final int TREE_MAX_SURFACE_DROP = 2;
   private static final int TREE_MAX_SURFACE_RISE = 3;
   private static final int ABANDONED_CAMP_TREE_CLEARANCE = 2;
   private static final int SHORELINE_BANK_RAMP_MAX_SLOPE = 1;
   private static final int SHORELINE_BANK_RAMP_MIN_CLIFF = 3;
   private static final int OCEAN_SHORE_MAX_DISTANCE = 8;
   private static final int INLAND_SHORE_MAX_DISTANCE = 4;
   private static final int FULL_CHUNK_OCEAN_BEACH_MAX_DISTANCE = intProperty("tellus.chunkgen.oceanBeachMaxDistance", 4, 0, OCEAN_SHORE_MAX_DISTANCE);
   private static final int SHORELINE_OUTER_NOISE_BAND = 2;
   private static final int SHALLOW_SHORE_WATER_DEPTH = 3;
   private static final int SHALLOW_SHORE_WATER_DISTANCE = 2;

   private static final BlockState AIR_STATE = Blocks.AIR.defaultBlockState();

   private static final BlockState STONE_STATE = Blocks.STONE.defaultBlockState();

   private static final BlockState DEEPSLATE_STATE = Blocks.DEEPSLATE.defaultBlockState();

   private static final BlockState WATER_STATE = Blocks.WATER.defaultBlockState();

   private static final BlockState BEDROCK_STATE = Blocks.BEDROCK.defaultBlockState();

   private static final BlockState CAVE_AIR_STATE = Blocks.CAVE_AIR.defaultBlockState();

   private static final BlockState DIRT_STATE = Blocks.DIRT.defaultBlockState();

   private static final BlockState SAND_STATE = Blocks.SAND.defaultBlockState();

   private static final BlockState RED_SAND_STATE = Blocks.RED_SAND.defaultBlockState();

   private static final BlockState SANDSTONE_STATE = Blocks.SANDSTONE.defaultBlockState();

   private static final BlockState TERRACOTTA_STATE = Blocks.TERRACOTTA.defaultBlockState();

   private static final BlockState GRASS_BLOCK_STATE = Blocks.GRASS_BLOCK.defaultBlockState();
   private static final BlockState MYCELIUM_STATE = Blocks.MYCELIUM.defaultBlockState();

   private static final BlockState PODZOL_STATE = Blocks.PODZOL.defaultBlockState();

   private static final BlockState COARSE_DIRT_STATE = Blocks.COARSE_DIRT.defaultBlockState();

   private static final BlockState ROOTED_DIRT_STATE = Blocks.ROOTED_DIRT.defaultBlockState();

   private static final BlockState MUD_STATE = Blocks.MUD.defaultBlockState();

   private static final BlockState PACKED_MUD_STATE = Blocks.PACKED_MUD.defaultBlockState();

   private static final BlockState MOSS_BLOCK_STATE = Blocks.MOSS_BLOCK.defaultBlockState();

   private static final BlockState GRAVEL_STATE = Blocks.GRAVEL.defaultBlockState();

   private static final BlockState CLAY_STATE = Blocks.CLAY.defaultBlockState();

   private static final BlockState POWDER_SNOW_STATE = Blocks.POWDER_SNOW.defaultBlockState();

   private static final BlockState SNOW_BLOCK_STATE = Blocks.SNOW_BLOCK.defaultBlockState();

   private static final BlockState SNOW_LAYER_STATE = Objects.requireNonNull(Blocks.SNOW.defaultBlockState(), "snowLayerState");

   private static final BlockState ANDESITE_STATE = Blocks.ANDESITE.defaultBlockState();

   private static final BlockState DIORITE_STATE = Blocks.DIORITE.defaultBlockState();

   private static final BlockState COBBLESTONE_STATE = Blocks.COBBLESTONE.defaultBlockState();

   private static final BlockState PACKED_ICE_STATE = Blocks.PACKED_ICE.defaultBlockState();

   private static final BlockState BLUE_ICE_STATE = Blocks.BLUE_ICE.defaultBlockState();
   private static final long SURFACE_VARIANT_SALT = 2870177441569192885L;
   private static final long OCEAN_FLOOR_SALT = 8006659316467387678L;
   private static final long EXPOSED_ROCK_SALT = -3076553442396315309L;
   private static final long ALPINE_SCREE_SALT = 5733126890612783389L;
   private static final int MOUNTAIN_ZONE_ROW_SHIFT = 4;
   private static final int MOUNTAIN_ZONE_ROW_CACHE_SIZE = 1024;
   private static final int MOUNTAIN_ZONE_CELL_SHIFT = 5;
   private static final int MOUNTAIN_ZONE_CACHE_BITS = 12;
   private static final AtomicBoolean LOGGED_CHUNK_LAYOUT = new AtomicBoolean(false);
   private static final Map<EarthChunkGenerator.BiomeSettingsKey, BiomeGenerationSettings> FILTERED_SETTINGS = new ConcurrentHashMap<>();
   private static final Map<EarthChunkGenerator.TreeFeatureSettingsKey, List<TreeFeatureHandle>> TREE_FEATURES = new ConcurrentHashMap<>();
   private final EarthGeneratorSettings settings;
   private final int seaLevel;
   private final int minY;
   private final int height;
   private final WaterSurfaceResolver waterResolver;
   private final AntarcticSnowPolicy antarcticSnowPolicy;
   private final TerrainPreloadPackageRegistry.SettingsView preloadedTerrain;
   private volatile TellusVanillaCarverRunner tellusCarverRunner;
   private final ThreadLocal<EarthChunkGenerator.WaterChunkCache> waterChunkCache = ThreadLocal.withInitial(EarthChunkGenerator.WaterChunkCache::new);
   private final ThreadLocal<EarthChunkGenerator.OsmOverlayScratch> osmOverlayScratch = ThreadLocal.withInitial(EarthChunkGenerator.OsmOverlayScratch::new);
   private final ThreadLocal<EarthChunkGenerator.LodMountainTransitionCache> lodMountainTransitionCache = new ThreadLocal<>();
   private final ThreadLocal<EarthChunkGenerator.MountainSamplingCache> mountainSamplingCache = ThreadLocal.withInitial(
      () -> new MountainSamplingCache()
   );
   private final SnowTransitionPolicy.SourceSampler snowTransitionSourceSampler = this::hasPersistentSnowSourceAt;
   // Latitude-curve anchors per 16-block row, for columns too low for the local treeline to matter.
   private final EarthChunkGenerator.MountainZoneRow[] mountainZoneRows = new EarthChunkGenerator.MountainZoneRow[MOUNTAIN_ZONE_ROW_CACHE_SIZE];
   private final LocalTreeline localTreeline;
   // Zone anchors per 32-block cell. Entries are immutable, so racing writers are harmless.
   private final EarthChunkGenerator.MountainZoneCell[] mountainZoneCells = new EarthChunkGenerator.MountainZoneCell[1 << MOUNTAIN_ZONE_CACHE_BITS];
   private final ThreadLocal<Boolean> lodShorelineOverrideSuppressed = new ThreadLocal<>();
   private final ThreadLocal<Double> lodSlopeResolutionMeters = new ThreadLocal<>();
   private final ThreadLocal<LodSnowSlope.Level> lodSnowSlopeLevel = new ThreadLocal<>();
   private final ThreadLocal<Double> lodSandResolutionMeters = new ThreadLocal<>();
   private final Map<Long, EarthChunkGenerator.PreparedChunkBuildings> preparedChunkBuildings = new ConcurrentHashMap<>();
   private final Map<Long, EarthChunkGenerator.PreparedChunkRoadLights> preparedChunkRoadLights = new ConcurrentHashMap<>();
   private final Map<Long, EarthChunkGenerator.ChunkDecorationContext> chunkDecorationContexts = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<Long, Long> preparedChunkStateTouchedAt = new ConcurrentHashMap<>();
   private final EarthChunkGenerator.HeightGridCache heightGridCache = new EarthChunkGenerator.HeightGridCache(HEIGHT_GRID_CACHE_ENTRIES);
   private final EarthChunkGenerator.ChunkDetailManager chunkDetailManager = new EarthChunkGenerator.ChunkDetailManager();
   private final EarthChunkGenerator.TerrainRefinementManager terrainRefinementManager = new EarthChunkGenerator.TerrainRefinementManager();
   private final int configuredSpawnChunkX;
   private final int configuredSpawnChunkZ;
   private volatile long worldSeed = 0L;
   private static final long JAVA_RANDOM_MULTIPLIER = 25214903917L;
   private static final long JAVA_RANDOM_ADDEND = 11L;
   private static final long JAVA_RANDOM_MASK = 281474976710655L;
   private final AtomicBoolean fastSpawnMode = new AtomicBoolean(true);
   private final AtomicLong chunkDetailGenerationSequence = new AtomicLong();
   private final ConcurrentHashMap<Long, Long> terrainGenerationStamps = new ConcurrentHashMap<>();
   private final TellusLandmarks landmarks;

   public EarthChunkGenerator(BiomeSource biomeSource, EarthGeneratorSettings settings) {
      super(biomeSource, biome -> generationSettingsForBiome(biome, settings));
      this.settings = settings;
      this.seaLevel = settings.effectiveHeightOffset();
      EarthGeneratorSettings.HeightLimits limits = EarthGeneratorSettings.resolveHeightLimits(settings);
      ExperimentalHeightSupport.validateOrThrow(settings, limits);
      this.minY = limits.minY();
      this.height = limits.height();
      this.waterResolver = TellusWorldgenSources.waterResolver(settings);
      this.antarcticSnowPolicy = AntarcticSnowPolicy.forWorldScale(settings.worldScale());
      this.localTreeline = LocalTreeline.forSettings(settings);
      this.preloadedTerrain = TerrainPreloadPackageRegistry.instance().viewFor(settings);
      double blocksPerDegree = blocksPerDegree(settings.worldScale());
      int spawnBlockX = Mth.floor(settings.spawnLongitude() * blocksPerDegree);
      int spawnBlockZ = Mth.floor(EarthProjection.latToBlockZ(settings.spawnLatitude(), settings.worldScale()));
      this.configuredSpawnChunkX = Math.floorDiv(spawnBlockX, 16);
      this.configuredSpawnChunkZ = Math.floorDiv(spawnBlockZ, 16);
      this.landmarks = TellusLandmarks.create(settings, this::sampleSurfaceHeight);
      if (biomeSource instanceof EarthBiomeSource earthBiomeSource) {
         earthBiomeSource.setFastSpawnMode(true);
      }

      if (Tellus.LOGGER.isInfoEnabled()) {
         Tellus.LOGGER
            .info(
               "EarthChunkGenerator init: scale={}, minAltitude={}, maxAltitude={}, heightOffset={}, limits=[minY={}, height={}, logicalHeight={}], seaLevel={}",
               new Object[]{
                  settings.worldScale(),
                  settings.minAltitude(),
                  settings.maxAltitude(),
                  settings.heightOffset(),
                  limits.minY(),
                  limits.height(),
                  limits.logicalHeight(),
                  this.seaLevel
               }
            );
      }
   }

   public static EarthChunkGenerator create(Provider registries, EarthGeneratorSettings settings) {
      return new EarthChunkGenerator(new EarthBiomeSource(registries.lookupOrThrow(Registries.BIOME), settings), settings);
   }

   public EarthGeneratorSettings settings() {
      return this.settings;
   }

   public TellusLandmarks landmarks() {
      return this.landmarks;
   }

   public boolean shouldSuppressWaterSourceConversion(int blockX, int blockZ) {
      return this.waterResolver.shouldSuppressWaterSourceConversion(blockX, blockZ);
   }

   public boolean shouldSuppressWaterSourceConversion(int blockX, int blockY, int blockZ) {
      return this.waterResolver.shouldSuppressWaterSourceConversion(blockX, blockY, blockZ);
   }

   public boolean isLakeWater(int blockX, int blockZ) {
      return this.waterResolver.isLakeWater(blockX, blockZ);
   }

   public long worldSeed() {
      return this.worldSeed;
   }

   public int getUndergroundPlacementSurfaceY(int blockX, int blockZ) {
      EarthChunkGenerator.ChunkDecorationContext context = this.chunkDecorationContexts.get(
         MinecraftVersionCompat.packChunkPos(blockX >> 4, blockZ >> 4)
      );
      return UndergroundPlacementSurfacePolicy.resolve(
         context != null ? context.terrainSurfaces() : null,
         blockX,
         blockZ,
         this::sampleSurfaceHeight
      );
   }

   public boolean isUndergroundStructureFeaturePlacementBlocked(int blockX, int blockY, int blockZ) {
      EarthChunkGenerator.ChunkDecorationContext context = this.chunkDecorationContexts.get(
         MinecraftVersionCompat.packChunkPos(blockX >> 4, blockZ >> 4)
      );
      return context != null
         && UndergroundStructureExclusion.blocksFeaturePlacement(
            context.undergroundStructureExclusions(), blockX, blockY, blockZ
         );
   }


   public ChunkGeneratorStructureState createState( HolderLookup<StructureSet> structureSets,  RandomState randomState, long seed) {
      this.worldSeed = seed;
      HolderLookup<StructureSet> filtered = new EarthChunkGenerator.FilteredStructureLookup(structureSets, this::isStructureSetEnabled);
      return this.createStructureState(randomState, seed, filtered);
   }

   @Override
   public Pair<BlockPos, Holder<Structure>> findNearestMapStructure(
      ServerLevel level, HolderSet<Structure> structures, BlockPos position, int searchRadius, boolean skipKnownStructures
   ) {
      // Only explorer maps (camp chests, cartographers) claim what they find. Vanilla predicts their targets across 50 to 100
      // rings of unexplored terrain on the server thread, and each Tellus prediction loads real-world data, freezing the game.
      if (skipKnownStructures) {
         return null;
      }

      boolean structureBiomeQueries = this.settings.caveGeneration()
         && this.settings.deepDark()
         && this.settings.addAncientCities()
         && structures.stream().anyMatch(holder -> holder.is(BuiltinStructures.ANCIENT_CITY));
      EarthBiomeSource earthBiomeSource = structureBiomeQueries && this.biomeSource instanceof EarthBiomeSource source ? source : null;
      if (earthBiomeSource == null) {
         return super.findNearestMapStructure(level, structures, position, searchRadius, skipKnownStructures);
      }

      earthBiomeSource.beginStructureBiomeQueries();
      try {
         return super.findNearestMapStructure(level, structures, position, searchRadius, skipKnownStructures);
      } finally {
         earthBiomeSource.endStructureBiomeQueries();
      }
   }

   public BlockPos getInitialSpawnPosition(LevelHeightAccessor heightAccessor) {
      return this.getSurfacePosition(heightAccessor, this.settings.spawnLatitude(), this.settings.spawnLongitude(), false);
   }

   public BlockPos getSurfacePosition(LevelHeightAccessor heightAccessor, double latitude, double longitude) {
      return this.getSurfacePosition(heightAccessor, latitude, longitude, true);
   }

   private BlockPos getSurfacePosition(LevelHeightAccessor heightAccessor, double latitude, double longitude, boolean useDetailedWaterResolver) {
      double blocksPerDegree = blocksPerDegree(this.settings.worldScale());
      int spawnX = Mth.floor(longitude * blocksPerDegree);
      int spawnZ = Mth.floor(EarthProjection.latToBlockZ(latitude, this.settings.worldScale()));
      ExperimentalHeightSupport.validateHorizontalPositionOrThrow(
         this.settings, spawnX, spawnZ, "surface target lat=" + latitude + ", lon=" + longitude
      );
      int coverClass = this.sampleCoverClass(spawnX, spawnZ);
      int surface;
      if (useDetailedWaterResolver) {
         WaterSurfaceResolver.WaterColumnData column = this.settings.enableWater()
            ? this.resolveOsmWaterColumn(spawnX, spawnZ, coverClass, true)
            : this.waterResolver.resolveColumnData(spawnX, spawnZ, coverClass);
         surface = column.terrainSurface();
         if (column.hasWater()) {
            surface = Math.max(surface, column.waterSurface());
         }
      } else {
         WaterSurfaceResolver.WaterColumnData column = this.resolveLodWaterColumn(spawnX, spawnZ, coverClass);
         surface = column.hasWater() ? Math.max(column.terrainSurface(), column.waterSurface()) : column.terrainSurface();
      }

      int maxY = MinecraftVersionCompat.maxY(heightAccessor) - 1;
      int spawnY = Mth.clamp(surface + 1, MinecraftVersionCompat.minY(heightAccessor), maxY);
      ExperimentalHeightSupport.validateBlockPositionOrThrow(
         this.settings, spawnX, spawnY, spawnZ, "surface target lat=" + latitude + ", lon=" + longitude
      );
      return new BlockPos(spawnX, spawnY, spawnZ);
   }

   public double longitudeFromBlock(double blockX) {
      return blockX / blocksPerDegree(this.settings.worldScale());
   }

   public double latitudeFromBlock(double blockZ) {
      return EarthProjection.blockZToLat(blockZ, this.settings.worldScale());
   }

   private static double blocksPerDegree(double worldScale) {
      return EarthProjection.blocksPerDegree(worldScale);
   }

   private static int intProperty(String key, int defaultValue, int minInclusive, int maxInclusive) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      } else {
         try {
            return Mth.clamp(Integer.parseInt(value), minInclusive, maxInclusive);
         } catch (NumberFormatException var6) {
            Tellus.LOGGER.debug("Invalid integer system property {}='{}', using {}", new Object[]{key, value, defaultValue});
            return defaultValue;
         }
      }
   }

   private static EarthChunkGenerator.SurfaceMode surfaceModeProperty(String key, EarthChunkGenerator.SurfaceMode defaultValue) {
      String value = System.getProperty(key);
      if (value == null || value.isBlank()) {
         return defaultValue;
      }

      try {
         return EarthChunkGenerator.SurfaceMode.valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException error) {
         Tellus.LOGGER.debug("Invalid surface mode system property {}='{}', using {}", new Object[]{key, value, defaultValue});
         return defaultValue;
      }
   }

   private boolean usesDeferredChunkDetails() {
      return this.shouldDeferRoadDetails() || this.shouldDeferBuildingDetails() || this.shouldDeferDetailedWater() || this.shouldDeferTrees();
   }

   private boolean usesDeferredTerrainRefinement() {
      return DEFER_TERRAIN_REFINEMENT && MEMORY_ONLY_TERRAIN_CRITICAL_PATH;
   }

   private boolean shouldForceExactSpawnTerrain(ChunkPos pos) {
      return Math.abs(MinecraftVersionCompat.chunkX(pos) - this.configuredSpawnChunkX) <= SPAWN_EXACT_CHUNK_RADIUS
         && Math.abs(MinecraftVersionCompat.chunkZ(pos) - this.configuredSpawnChunkZ) <= SPAWN_EXACT_CHUNK_RADIUS;
   }

   private boolean hasDeferredApplyWork() {
      return this.shouldDeferRoadDetails() || this.shouldDeferBuildingDetails() || this.shouldDeferTrees();
   }

   private boolean hasPendingTerrainRefinement(ChunkPos pos) {
      return this.terrainRefinementManager.hasPending(pos);
   }

   public void discardPreparedChunkState(ChunkPos pos) {
      this.discardPreparedChunkState(MinecraftVersionCompat.packChunkPos(MinecraftVersionCompat.chunkX(pos), MinecraftVersionCompat.chunkZ(pos)));
   }

   private void discardPreparedChunkState(long chunkKey) {
      this.preparedChunkBuildings.remove(chunkKey);
      this.preparedChunkRoadLights.remove(chunkKey);
      this.chunkDecorationContexts.remove(chunkKey);
      this.preparedChunkStateTouchedAt.remove(chunkKey);
   }

   private void markPreparedChunkState(long chunkKey) {
      this.preparedChunkStateTouchedAt.put(chunkKey, System.nanoTime());
   }

   private void clearPreparedChunkStateTracking(long chunkKey) {
      if (!this.preparedChunkBuildings.containsKey(chunkKey)
         && !this.preparedChunkRoadLights.containsKey(chunkKey)
         && !this.chunkDecorationContexts.containsKey(chunkKey)) {
         this.preparedChunkStateTouchedAt.remove(chunkKey);
      }
   }

   private void reapStalePreparedChunkState(long gameTime) {
      if (PREPARED_CHUNK_STATE_STALE_NANOS <= 0L
         || PREPARED_CHUNK_STATE_REAP_INTERVAL_TICKS <= 0
         || gameTime % PREPARED_CHUNK_STATE_REAP_INTERVAL_TICKS != 0L) {
         return;
      }

      long now = System.nanoTime();
      for (Entry<Long, Long> entry : this.preparedChunkStateTouchedAt.entrySet()) {
         long chunkKey = entry.getKey();
         long touchedAt = entry.getValue();
         if (now - touchedAt < PREPARED_CHUNK_STATE_STALE_NANOS) {
            continue;
         }

         if (this.preparedChunkStateTouchedAt.remove(chunkKey, touchedAt)) {
            this.preparedChunkBuildings.remove(chunkKey);
            this.preparedChunkRoadLights.remove(chunkKey);
            this.chunkDecorationContexts.remove(chunkKey);
         }
      }
   }

   private boolean shouldDeferRoadDetails() {
      double worldScale = this.settings.worldScale();
      return !CHUNK_DETAIL_LEGACY_BLOCKING && CHUNK_DETAIL_DEFER_ROADS && this.settings.enableRoads() && worldScale > 0.0 && worldScale <= OSM_ROAD_MAX_SCALE;
   }

   private boolean shouldDeferBuildingDetails() {
      double worldScale = this.settings.worldScale();
      return !CHUNK_DETAIL_LEGACY_BLOCKING
         && CHUNK_DETAIL_DEFER_BUILDINGS
         && this.settings.enableBuildings()
         && OSM_BUILDING_SOURCE.available()
         && worldScale > 0.0
         && worldScale <= OSM_BUILDING_MAX_SCALE;
   }

   private boolean shouldDeferDetailedWater() {
      return !CHUNK_DETAIL_LEGACY_BLOCKING && CHUNK_DETAIL_DEFER_DETAILED_WATER && this.settings.enableWater();
   }

   private boolean shouldDeferTrees() {
      return !CHUNK_DETAIL_LEGACY_BLOCKING && CHUNK_DETAIL_DEFER_TREES;
   }

   private boolean shouldUseStructureOsmSyncFallback() {
      return CHUNK_DETAIL_LEGACY_BLOCKING;
   }


   @Override
   public CompletableFuture<ChunkAccess> createBiomes(RandomState random, Blender blender, StructureManager structures, ChunkAccess chunk) {
      this.disableFastSpawnMode();
      return MinecraftVersionCompat.supplyBackground("init_biomes", () -> this.fillBiomesWithColumnCache(random, chunk));
   }

   private ChunkAccess fillBiomesWithColumnCache(RandomState random, ChunkAccess chunk) {
      EarthChunkGenerator.FullChunkTrace timingTrace = EarthChunkGenerator.FullChunkPerf.beginTrace("biomes", chunk.getPos());
      Throwable failure = null;
      try {
         BiomeResolver resolver = this.biomeSource instanceof EarthBiomeSource earthBiomeSource
            ? earthBiomeSource.createChunkBiomeResolver()
            : WorldgenApi.biomeResolver(this.biomeSource, random);
         WorldgenApi.fillBiomes(chunk, resolver, random);
         return chunk;
      } catch (RuntimeException | Error error) {
         failure = error;
         throw error;
      } finally {
         EarthChunkGenerator.FullChunkPerf.finishTrace(timingTrace, failure == null ? "success" : "failed", failure);
      }
   }

   public void applyCarvers(
       WorldGenRegion level,
      long seed,
       RandomState random,
      BiomeManager biomeManager,
       StructureManager structures,
      ChunkAccess chunk
   ) {
      List<UndergroundStructureExclusion.Box> structureExclusions =
         this.collectUndergroundStructureExclusions(structures, chunk.getPos());
      long chunkKey = MinecraftVersionCompat.packChunkPos(
         MinecraftVersionCompat.chunkX(chunk.getPos()), MinecraftVersionCompat.chunkZ(chunk.getPos())
      );
      this.chunkDecorationContexts.computeIfPresent(
         chunkKey,
         (ignored, context) -> context.withUndergroundStructureExclusions(structureExclusions)
      );

      EarthChunkGenerator.FullChunkTrace timingTrace = EarthChunkGenerator.FullChunkPerf.beginTrace("carvers", chunk.getPos());
      try {
         long totalStartNs = beginFullChunkProfiling();
         boolean applyCaves = !SharedConstants.DEBUG_DISABLE_CARVERS && this.settings.caveGeneration();
         boolean applyOreVeins = this.settings.oreDistribution();
         boolean applyGeologicalStonePatches = this.settings.geologicalStonePatches();
         if ((applyCaves || applyOreVeins || applyGeologicalStonePatches)
            && !this.settings.suppressesUndergroundGenerationForTerrainShell()) {
            long phaseStartNs = beginFullChunkProfiling();
            boolean[] waterFlags = new boolean[CHUNK_AREA];
            int[] terrainSurfaceYByColumn = new int[CHUNK_AREA];
            WaterSurfaceResolver.WaterChunkData waterData = this.resolveChunkWaterData(chunk.getPos());
            int waterColumnCount = 0;

            for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
               for (int localX = 0; localX < CHUNK_SIDE; localX++) {
                  boolean hasWater = waterData.hasWater(localX, localZ);
                  int index = chunkIndex(localX, localZ);
                  waterFlags[index] = hasWater;
                  terrainSurfaceYByColumn[index] = waterData.terrainSurface(localX, localZ);
                  if (hasWater) {
                     waterColumnCount++;
                  }
               }
            }

            boolean[] floodGuardColumns = computeFloodGuardColumns(waterFlags);
            int defaultFloodGuardY = this.seaLevel - SPAGHETTI_WATER_GUARD_DEPTH;
            int[] floodGuardYByColumn = new int[CHUNK_AREA];
            Arrays.fill(floodGuardYByColumn, Integer.MAX_VALUE);

            for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
               for (int localXx = 0; localXx < CHUNK_SIDE; localXx++) {
                  int index = chunkIndex(localXx, localZ);
                  if (floodGuardColumns[index]) {
                     floodGuardYByColumn[index] = defaultFloodGuardY;
                  }
               }
            }

            if (waterColumnCount >= Math.ceil(CHUNK_AREA * OCEAN_CHUNK_CARVER_GUARD_RATIO)) {
               for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
                  for (int localXxx = 0; localXxx < CHUNK_SIDE; localXxx++) {
                     int index = chunkIndex(localXxx, localZ);
                     if (floodGuardColumns[index] && waterData.hasWater(localXxx, localZ)) {
                        int terrainSurface = waterData.terrainSurface(localXxx, localZ);
                        int oceanFloorGuardY = Math.max(MinecraftVersionCompat.minY(chunk), terrainSurface - OCEAN_CARVER_FLOOR_BUFFER);
                        floodGuardYByColumn[index] = Math.min(floodGuardYByColumn[index], oceanFloorGuardY);
                     }
                  }
               }
            }

            endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.CARVERS_WATER_GUARD, phaseStartNs);
            phaseStartNs = beginFullChunkProfiling();
            int[] generationFloorYByColumn = this.settings.usesTerrainShell()
               ? this.computeCarverGenerationFloorYByColumn(chunk, waterData)
               : null;
            this.getTellusCarverRunner(level.registryAccess())
               .applyCarvers(
                  level,
                  seed,
                  biomeManager,
                  structures,
                  chunk,
                  this.seaLevel,
                  applyCaves,
                  this.settings.cavesReachSurface(),
                  applyOreVeins,
                  applyGeologicalStonePatches,
                  terrainSurfaceYByColumn,
                  this::sampleSurfaceHeight,
                  floodGuardYByColumn,
                  generationFloorYByColumn,
                  structureExclusions
               );
            endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.CARVERS_RUNNER, phaseStartNs);
         }

         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.CARVERS_TOTAL, totalStartNs);
         EarthChunkGenerator.FullChunkPerf.finishTrace(timingTrace, "success", null);
      } catch (RuntimeException | Error error) {
         EarthChunkGenerator.FullChunkPerf.finishTrace(timingTrace, "failed", error);
         throw error;
      }
   }

   public void buildSurface( WorldGenRegion level,  StructureManager structures,  RandomState random,  ChunkAccess chunk) {
   }

   public void spawnOriginalMobs( WorldGenRegion level) {
      ChunkPos center = level.getCenter();
      WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
      random.setDecorationSeed(level.getSeed(), center.getMinBlockX(), center.getMinBlockZ());
      WorldgenApi.spawnMobs(level, center, random);
   }

   public void applyBiomeDecoration( WorldGenLevel level,  ChunkAccess chunk,  StructureManager structures) {
      EarthChunkGenerator.FullChunkTrace timingTrace = EarthChunkGenerator.FullChunkPerf.beginTrace("decoration", chunk.getPos());
      long chunkKey = MinecraftVersionCompat.packChunkPos(
         MinecraftVersionCompat.chunkX(chunk.getPos()), MinecraftVersionCompat.chunkZ(chunk.getPos())
      );
      long totalStartNs = beginFullChunkProfiling();
      long phaseStartNs;
      Throwable failure = null;
      try {
         phaseStartNs = beginFullChunkProfiling();
         super.applyBiomeDecoration(level, chunk, structures);
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.DECORATION_SUPER, phaseStartNs);
         if (this.settings.usesTerrainShell()) {
            EarthChunkGenerator.ChunkDecorationContext context = this.chunkDecorationContexts.get(chunkKey);
            this.applyUndergroundStructureProtection(
               structures, chunk, context != null ? context.terrainSurfaces() : null, true
            );
         }
         if (this.settings.caveGeneration() && !this.settings.suppressesUndergroundGenerationForTerrainShell()) {
            phaseStartNs = beginFullChunkProfiling();
            this.spawnAxolotlsInLushPonds(level, chunk);
            endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.DECORATION_AXOLOTLS, phaseStartNs);
         }

         boolean delayTellusDecoration = this.hasPendingTerrainRefinement(chunk.getPos());
         if (delayTellusDecoration) {
            EarthChunkGenerator.TerrainStreamingPerf.recordDetailDelayedByRefinement();
         }
         List<BoundingBox> campPieces = this.abandonedCampPieces(level.registryAccess(), structures, chunk.getPos());
         phaseStartNs = beginFullChunkProfiling();
         if (!delayTellusDecoration && !this.shouldDeferTrees()) {
            this.placeTrees(level, chunk, campPieces);
         }
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.DECORATION_TREES, phaseStartNs);
         if (!delayTellusDecoration && !this.shouldDeferBuildingDetails()) {
            phaseStartNs = beginFullChunkProfiling();
            this.placePreparedBuildings(level, chunk);
            endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.DECORATION_BUILDINGS, phaseStartNs);
         }

         phaseStartNs = beginFullChunkProfiling();
         if (!delayTellusDecoration) {
            this.applyRealtimeSnowCover(level, chunk);
         }
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.DECORATION_REALTIME_SNOW, phaseStartNs);
         if (!delayTellusDecoration && !this.shouldDeferRoadDetails()) {
            phaseStartNs = beginFullChunkProfiling();
            this.placePreparedRoadLights(level, chunk);
            endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.DECORATION_ROAD_LIGHTS, phaseStartNs);
         }

         if (!delayTellusDecoration && this.hasDeferredApplyWork()) {
            phaseStartNs = beginFullChunkProfiling();
            this.applyReadyDeferredChunkDetail(level, chunk, campPieces);
            endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.DECORATION_DEFERRED_APPLY, phaseStartNs);
         }

         // Last, so the landmark wins over trees, lamps and anything else placed in the same columns.
         if (!delayTellusDecoration) {
            this.placeLandmarks(level, chunk);
         }

         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.DECORATION_TOTAL, totalStartNs);
      } catch (RuntimeException | Error error) {
         failure = error;
         throw error;
      } finally {
         this.chunkDecorationContexts.remove(chunkKey);
         this.clearPreparedChunkStateTracking(chunkKey);
         EarthChunkGenerator.FullChunkPerf.finishTrace(timingTrace, failure == null ? "success" : "failed", failure);
      }
   }

   public void createStructures(
       RegistryAccess registryAccess,
       ChunkGeneratorStructureState structureState,
       StructureManager structures,
       ChunkAccess chunk,
       StructureTemplateManager templates,
       ResourceKey<Level> levelKey
   ) {
      EarthChunkGenerator.FullChunkTrace timingTrace = EarthChunkGenerator.FullChunkPerf.beginTrace("structures", chunk.getPos());
      try {
         long totalStartNs = beginFullChunkProfiling();
         long phaseStartNs = beginFullChunkProfiling();
         EarthBiomeSource earthBiomeSource = this.biomeSource instanceof EarthBiomeSource source ? source : null;
         boolean structureBiomeQueries = earthBiomeSource != null
            && this.settings.caveGeneration()
            && this.settings.deepDark()
            && this.settings.addAncientCities();
         if (structureBiomeQueries) {
            earthBiomeSource.beginStructureBiomeQueries();
         }

         try {
            super.createStructures(registryAccess, structureState, structures, chunk, templates, levelKey);
         } finally {
            if (structureBiomeQueries) {
               earthBiomeSource.endStructureBiomeQueries();
            }
         }

         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.STRUCTURES_SUPER, phaseStartNs);
         phaseStartNs = beginFullChunkProfiling();
         this.filterVillageStarts(registryAccess, chunk);
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.STRUCTURES_VILLAGES, phaseStartNs);
         if (this.settings.addAbandonedCamps()) {
            phaseStartNs = beginFullChunkProfiling();
            this.filterAbandonedCampStarts(registryAccess, chunk);
            endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.STRUCTURES_ABANDONED_CAMPS, phaseStartNs);
         }

         if (this.settings.addWoodlandMansions()) {
            phaseStartNs = beginFullChunkProfiling();
            this.filterWoodlandMansionStarts(registryAccess, chunk);
            endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.STRUCTURES_WOODLAND_MANSIONS, phaseStartNs);
         }

         if (this.settings.addStrongholds()) {
            phaseStartNs = beginFullChunkProfiling();
            this.retargetStrongholdStarts(registryAccess, chunk);
            endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.STRUCTURES_STRONGHOLDS, phaseStartNs);
         }

         if (this.settings.addMineshafts()) {
            phaseStartNs = beginFullChunkProfiling();
            this.retargetMineshaftStarts(registryAccess, chunk);
            endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.STRUCTURES_MINESHAFTS, phaseStartNs);
         }

         if (this.settings.addAncientCities()) {
            phaseStartNs = beginFullChunkProfiling();
            this.retargetAncientCityStarts(registryAccess, chunk);
            endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.STRUCTURES_ANCIENT_CITIES, phaseStartNs);
         }

         if (this.settings.addTrialChambers()) {
            phaseStartNs = beginFullChunkProfiling();
            this.retargetTrialChamberStarts(registryAccess, chunk);
            endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.STRUCTURES_TRIAL_CHAMBERS, phaseStartNs);
         }

         if (this.settings.addOceanMonuments()) {
            phaseStartNs = beginFullChunkProfiling();
            this.adjustOceanMonumentStarts(registryAccess, chunk);
            endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.STRUCTURES_OCEAN_MONUMENTS, phaseStartNs);
         }

         phaseStartNs = beginFullChunkProfiling();
         this.filterStartsCollidingWithOsm(registryAccess, chunk);
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.STRUCTURES_OSM_COLLISIONS, phaseStartNs);
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.STRUCTURES_TOTAL, totalStartNs);
         EarthChunkGenerator.FullChunkPerf.finishTrace(timingTrace, "success", null);
      } catch (RuntimeException | Error error) {
         EarthChunkGenerator.FullChunkPerf.finishTrace(timingTrace, "failed", error);
         throw error;
      }
   }

   public void createReferences( WorldGenLevel level,  StructureManager structures,  ChunkAccess chunk) {
      super.createReferences(level, structures, chunk);
   }


   public CompletableFuture<ChunkAccess> fillFromNoise(
       Blender blender,  RandomState random,  StructureManager structures,  ChunkAccess chunk
   ) {
      EarthChunkGenerator.FullChunkTrace timingTrace = EarthChunkGenerator.FullChunkPerf.beginTrace("fill", chunk.getPos());
      try {
         this.disableFastSpawnMode();
         long baseTerrainStartNs = EarthChunkGenerator.ChunkDetailPerf.now();
         long fullChunkStartNs = beginFullChunkProfiling();
         this.fillTellusSurface(random, structures, chunk);
         EarthChunkGenerator.ChunkDetailPerf.recordBaseTerrain(EarthChunkGenerator.ChunkDetailPerf.elapsedSince(baseTerrainStartNs));
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_TOTAL, fullChunkStartNs);
         EarthChunkGenerator.FullChunkPerf.finishTrace(timingTrace, "success", null);
         return Objects.requireNonNull(CompletableFuture.completedFuture(chunk), "completedFuture");
      } catch (RuntimeException | Error error) {
         EarthChunkGenerator.FullChunkPerf.finishTrace(timingTrace, "failed", error);
         throw error;
      }
   }

   private void fillTellusSurface( RandomState random,  StructureManager structures,  ChunkAccess chunk) {
      ChunkPos pos = chunk.getPos();
      validateExperimentalChunkBounds(this.settings, pos);
      long chunkKey = MinecraftVersionCompat.packChunkPos(MinecraftVersionCompat.chunkX(pos), MinecraftVersionCompat.chunkZ(pos));
      long generationStamp = this.chunkDetailGenerationSequence.incrementAndGet();
      boolean terrainShellMode = this.usesDeferredTerrainRefinement() && !this.shouldForceExactSpawnTerrain(pos);
      boolean thinShellTerrain = this.settings.usesTerrainShell();
      this.discardPreparedChunkState(chunkKey);
      if (terrainShellMode) {
         this.terrainGenerationStamps.put(chunkKey, generationStamp);
      } else {
         this.terrainGenerationStamps.remove(chunkKey);
      }
      int unavailableElevationReads = TellusElevationSource.unavailableTileReads();
      long phaseStartNs = beginFullChunkProfiling();
      if (BLOCKING_TERRAIN_INPUT_WARMUP) {
         TellusWorldgenSources.warmCriticalTerrainInputsForChunk(pos, this.settings, this.settings.worldScale());
      }
      TellusWorldgenSources.prefetchForChunk(pos, this.settings, true, true, true, this.settings.worldScale(), false);
      endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_PREFETCH, phaseStartNs);
      int chunkMinY = MinecraftVersionCompat.minY(chunk);
      int chunkHeight = chunk.getHeight();
      int chunkMaxY = chunkMinY + chunkHeight;
      if (LOGGED_CHUNK_LAYOUT.compareAndSet(false, true) && Tellus.LOGGER.isInfoEnabled()) {
         Tellus.LOGGER
            .info(
               "fillFromNoise layout: chunkPos={}, minY={}, height={}, maxY={}, sections={}, genMinY={}, genHeight={}, seaLevel={}, settingsMinAlt={}, settingsMaxAlt={}",
               new Object[]{
                  pos,
                  chunkMinY,
                  chunkHeight,
                  chunkMinY + chunkHeight - 1,
                  chunkHeight >> 4,
                  this.minY,
                  this.height,
                  this.seaLevel,
                  this.settings.minAltitude(),
                  this.settings.maxAltitude()
               }
            );
      }

      int deepslateStart = this.minY + 64;
      boolean useFastFullChunk = FAST_FULL_CHUNK;
      boolean useFastSurfacePalette = useFastFullChunk && FULL_CHUNK_SURFACE_MODE == EarthChunkGenerator.SurfaceMode.TWO_TIER;
      MutableBlockPos cursor = new MutableBlockPos();
      int step = 4;
      int gridSize = 16 + step * 2;
      phaseStartNs = beginFullChunkProfiling();
      EarthChunkGenerator.TerrainShellHeightGridResult heightGridResult = terrainShellMode
         ? this.buildTerrainShellHeightGrid(pos, step, gridSize, useFastFullChunk)
         : EarthChunkGenerator.TerrainShellHeightGridResult.exact(
            this.buildHeightGrid(pos, step, gridSize, useFastFullChunk, NON_BLOCKING_TERRAIN_INPUTS)
         );
      int[] heightGrid = heightGridResult.heightGrid();

      int[] coverClasses = new int[CHUNK_AREA];
      int[] visualCoverClasses = new int[CHUNK_AREA];
      int[] surfaceCoverClasses = new int[CHUNK_AREA];
      boolean[] oceanFlags = new boolean[CHUNK_AREA];
      int[] terrainSurfaces = new int[CHUNK_AREA];
      copyChunkTerrainSurfaces(heightGrid, gridSize, step, terrainSurfaces);
      endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_HEIGHT_GRID, phaseStartNs);
      recordFullChunkProfilingCount(EarthChunkGenerator.FullChunkPhase.FILL_HEIGHT_GRID_CACHE_HIT, heightGridResult.cacheHits());
      recordFullChunkProfilingCount(EarthChunkGenerator.FullChunkPhase.FILL_HEIGHT_GRID_CACHE_MISS, heightGridResult.cacheMisses());

      phaseStartNs = beginFullChunkProfiling();
      boolean oceanUnresolved = false;
      WaterSurfaceResolver.WaterChunkData waterData;
      try {
         waterData = this.resolveChunkWaterData(pos, terrainSurfaces);
      } catch (OceanCoverageUnavailableException error) {
         waterData = WaterSurfaceResolver.WaterChunkData.fromArrays(
            terrainSurfaces, terrainSurfaces, new byte[CHUNK_AREA], true
         );
         oceanUnresolved = true;
         this.terrainGenerationStamps.put(chunkKey, generationStamp);
         Tellus.LOGGER.debug("Deferring unresolved Overture coastline for {}", pos, error);
      }
      if (!oceanUnresolved
         && waterData.approximate()
         && this.settings.enableWater()
         && this.shouldResolveApproximateWaterExactly(pos, terrainSurfaces)) {
         waterData = this.resolveExactChunkWaterData(pos);
      }
      endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_WATER_RESOLVE, phaseStartNs);
      int[] waterSurfaces = new int[CHUNK_AREA];
      boolean[] waterFlags = new boolean[CHUNK_AREA];
      int chunkMinX = pos.getMinBlockX();
      int chunkMinZ = pos.getMinBlockZ();
      int bedrockY = this.minY;
      boolean bedrockInChunk = bedrockY >= chunkMinY && bedrockY < chunkMaxY;
      long surfaceCoverResolveNs = 0L;
      int shellCoverMisses = 0;
      int shellVisualCoverMisses = 0;
      phaseStartNs = beginFullChunkProfiling();
      if (terrainShellMode || oceanUnresolved) {
         EarthChunkGenerator.TerrainShellColumnFillResult shellColumns = this.fillTerrainShellColumns(
            pos,
            chunkMinY,
            chunkMaxY,
            step,
            gridSize,
            heightGrid,
            waterData,
            terrainSurfaces,
            waterSurfaces,
            waterFlags,
            coverClasses,
            visualCoverClasses,
            surfaceCoverClasses,
            oceanFlags
         );
         surfaceCoverResolveNs = shellColumns.surfaceCoverResolveNs();
         shellCoverMisses = shellColumns.coverMisses();
         shellVisualCoverMisses = shellColumns.visualCoverMisses();
      } else {
         surfaceCoverResolveNs = this.fillExactChunkTerrainColumns(
            pos,
            chunkMinY,
            chunkMaxY,
            step,
            gridSize,
            heightGrid,
            waterData,
            null,
            terrainSurfaces,
            waterSurfaces,
            waterFlags,
            coverClasses,
            visualCoverClasses,
            surfaceCoverClasses,
            oceanFlags,
            true
         );
      }
      endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_COLUMN_RESOLVE, phaseStartNs);

      EarthChunkGenerator.PreparedChunkBuildings preparedBuildings = null;
      phaseStartNs = beginFullChunkProfiling();
      this.applyShorelineBankRamp(pos, terrainSurfaces, waterSurfaces, waterFlags, waterData);
      copyChunkTerrainSurfacesToHeightGrid(terrainSurfaces, heightGrid, gridSize, step);
      endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_SHORELINE_BANK_RAMP, phaseStartNs);
      if (!terrainShellMode) {
         phaseStartNs = beginFullChunkProfiling();
	         this.repairAnomalousChunkTerrain(
	            terrainSurfaces, waterSurfaces, waterFlags, oceanFlags, coverClasses, heightGrid, gridSize, step, chunkMinY, chunkMaxY - 1, waterData
	         );
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_TERRAIN_REPAIR, phaseStartNs);
         phaseStartNs = beginFullChunkProfiling();
         preparedBuildings = this.shouldDeferBuildingDetails()
            ? null
            : this.prepareChunkBuildings(pos, terrainSurfaces, chunkMinY, chunkMaxY - 1, random);
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BUILDING_PREP, phaseStartNs);
         phaseStartNs = beginFullChunkProfiling();
         this.applyPreparedBuildingsToTerrain(preparedBuildings, terrainSurfaces, waterSurfaces, waterFlags, chunkMinY, chunkMaxY - 1);
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BUILDING_TERRAIN, phaseStartNs);
      }
      this.applyLandmarkGroundToTerrain(pos, terrainSurfaces, waterSurfaces, waterFlags, chunkMinY, chunkMaxY - 1);
      int[] terrainShellBedrockSkinTopYs = thinShellTerrain
         ? this.computeTerrainShellBedrockSkinTopYs(
            terrainSurfaces, heightGrid, gridSize, step, chunkMinY, chunkMaxY - 1
         )
         : null;
      int[] terrainShellBedrockCurtainBottomYs = thinShellTerrain
         ? this.computeTerrainShellBedrockCurtainBottomYs(
            terrainSurfaces, heightGrid, gridSize, step, chunkMinY, chunkMaxY - 1
         )
         : null;
      int[] slopeDiffs = new int[CHUNK_AREA];
      int[] convexities = new int[CHUNK_AREA];
      Holder<Biome>[] biomeCache = newBiomeCache(CHUNK_AREA);
      EarthBiomeSource earthBiomeSource = this.biomeSource instanceof EarthBiomeSource typedEarthBiomeSource ? typedEarthBiomeSource : null;
      EarthChunkGenerator.ChunkBiomeClimateCache climateCache = useFastFullChunk && earthBiomeSource != null
         ? new EarthChunkGenerator.ChunkBiomeClimateCache(pos, this.settings.worldScale())
         : null;
      phaseStartNs = beginFullChunkProfiling();
      EarthChunkGenerator.FullChunkOceanBeachCache oceanBeachCache = this.buildFullChunkOceanBeachCache(
         chunkMinX, chunkMinZ, waterFlags, oceanFlags
      );

      this.fillChunkTerrainMetricsAndBiomes(
         pos,
         step,
         gridSize,
         heightGrid,
         terrainSurfaces,
         waterSurfaces,
         waterFlags,
         coverClasses,
         visualCoverClasses,
         oceanFlags,
         slopeDiffs,
         convexities,
         biomeCache,
         earthBiomeSource,
         climateCache,
         random,
         null
      );
      endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BIOME_CACHE, phaseStartNs);
      if (climateCache != null) {
         recordFullChunkProfilingCount(EarthChunkGenerator.FullChunkPhase.FILL_BIOME_CACHE_KOPPEN_HIT, climateCache.hitCount());
         recordFullChunkProfilingCount(EarthChunkGenerator.FullChunkPhase.FILL_BIOME_CACHE_KOPPEN_MISS, climateCache.missCount());
      }
      recordFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SURFACE_COVER_RESOLVE, surfaceCoverResolveNs);
      this.chunkDecorationContexts.put(
         chunkKey, EarthChunkGenerator.ChunkDecorationContext.capture(pos, terrainSurfaces, waterFlags, surfaceCoverClasses, biomeCache)
      );
      this.markPreparedChunkState(chunkKey);

      EarthChunkGenerator.TerrainWarmupTicket warmupTicket = new EarthChunkGenerator.TerrainWarmupTicket(
         heightGridResult.missingCount(), shellCoverMisses, shellVisualCoverMisses, waterData.approximate(), heightGridResult.usedFallback()
      );
      boolean elevationUnresolved = TellusElevationSource.unavailableTileReads() != unavailableElevationReads;
      if (elevationUnresolved) {
         this.terrainGenerationStamps.put(chunkKey, generationStamp);
         Tellus.LOGGER.debug("Deferring {} until its elevation tiles download", pos);
      }
      if (terrainShellMode || oceanUnresolved || elevationUnresolved) {
         phaseStartNs = beginFullChunkProfiling();
         EarthChunkGenerator.TerrainShellBuildResult shellResult = EarthChunkGenerator.TerrainShellBuildResult.capture(
            pos,
            chunkMinY,
            chunkMaxY - 1,
            terrainSurfaces,
            waterSurfaces,
            waterFlags,
            coverClasses,
            visualCoverClasses,
            surfaceCoverClasses,
            oceanFlags,
            biomeCache,
            warmupTicket,
            generationStamp
         );
         this.terrainRefinementManager.schedule(this, shellResult);
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_DETAIL_SCHEDULE, phaseStartNs);
      } else if (this.hasDeferredApplyWork()) {
         phaseStartNs = beginFullChunkProfiling();
         EarthChunkGenerator.ChunkGenerationContext detailContext = EarthChunkGenerator.ChunkGenerationContext.capture(
            pos,
            chunkMinY,
            chunkMaxY - 1,
            terrainSurfaces,
            waterSurfaces,
            waterFlags,
            surfaceCoverClasses,
            biomeCache,
            preparedBuildings,
            generationStamp
         );
         this.chunkDetailManager.schedule(this, detailContext);
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_DETAIL_SCHEDULE, phaseStartNs);
      }

      int minSurface = minSurfaceHeight(terrainSurfaces);
      LevelChunkSection[] sections = chunk.getSections();
      int sectionCount = sections.length;
      int[] sectionTopYs = new int[sectionCount];
      boolean[] solidSections = new boolean[sectionCount];

      for (int i = 0; i < sectionCount; i++) {
         sectionTopYs[i] = chunkMinY + (i << 4) + 15;
      }

      phaseStartNs = beginFullChunkProfiling();
      EarthChunkGenerator.SolidSectionFillProfiler solidSectionProfiler = new EarthChunkGenerator.SolidSectionFillProfiler();
      long columnStoneFillNs = 0L;
      long waterColumnFillNs = 0L;
      long surfaceApplyNs = 0L;
      long snowApplyNs = 0L;
      EarthChunkGenerator.ChunkSectionWriter sectionWriter = useFastFullChunk
         ? new EarthChunkGenerator.ChunkSectionWriter(sections, chunkMinY)
         : null;
      boolean[] columnFilledSections = useFastFullChunk ? null : new boolean[sectionCount];
      EarthChunkGenerator.SurfaceApplyProfiler surfaceProfiler = new EarthChunkGenerator.SurfaceApplyProfiler();

      try {
         long solidSectionsStartNs = beginFullChunkProfiling();
         long solidSectionsSubPhaseStartNs = beginFullChunkProfiling();
         int solidMaxIndex = resolveSolidSectionMaxIndex(chunk, chunkMinY, minSurface, sectionCount);
         solidSectionProfiler.maxIndexNs += elapsedFullChunkProfilingSince(solidSectionsSubPhaseStartNs);
         if (!thinShellTerrain && solidMaxIndex >= 0) {
            if (sectionWriter != null) {
               fillSolidSections(sectionWriter, solidSections, sectionTopYs, solidMaxIndex, STONE_STATE, DEEPSLATE_STATE, deepslateStart, solidSectionProfiler);
            } else {
               fillSolidSections(sections, solidSections, sectionTopYs, solidMaxIndex, STONE_STATE, DEEPSLATE_STATE, deepslateStart, solidSectionProfiler);
            }
         }
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SOLID_SECTIONS, solidSectionsStartNs);

         for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
            int worldZ = chunkMinZ + localZ;
            int rowIndex = localZ * CHUNK_SIDE;

            for (int localX = 0; localX < CHUNK_SIDE; localX++) {
               int worldX = chunkMinX + localX;
               int index = rowIndex + localX;
               int surface = terrainSurfaces[index];
               int waterSurface = waterSurfaces[index];
               boolean hasWater = waterFlags[index];
               Holder<Biome> biome = biomeCache[index];
               int y = chunkMinY;
               long subPhaseStartNs = beginFullChunkProfiling();
               int surfaceCoverClass = surfaceCoverClasses[index];
               boolean underwater = hasWater && waterSurface > surface;
               int slopeDiff = slopeDiffs[index];
               int convexity = convexities[index];
               BlockState mountainMassFill = underwater
                  ? null
                  : this.resolveMountainMassFillBlock(biome, surfaceCoverClass, surface, slopeDiff, convexity, worldX, worldZ);
               boolean surfaceSnow = !underwater && surface >= this.seaLevel
                  && this.shouldRetainSurfaceSnow(surfaceCoverClass, worldX, worldZ);
               boolean fullDepthSurfaceSnow = surfaceSnow
                  && SnowSlopePolicy.hasFullCoverage(this.sampleSnowSlopeDegrees(worldX, worldZ));
               boolean glacierHollow = surfaceSnow && MountainSurfaceRules.isGlacierHollow(slopeDiff, convexity);
               if (thinShellTerrain) {
                  int supportAnchorY = underwater ? waterSurface : surface;
                  boolean oceanSupport = underwater && oceanFlags[index];
                  int supportBottomY = this.resolveThinShellSupportBottomY(
                     localX, localZ, worldX, worldZ, surface, supportAnchorY, terrainSurfaces, chunkMinY, oceanSupport
                  );
                  int fillTopY = Math.min(chunkMaxY - 1, surface - 1);
                  int shellBedrockY = Math.min(supportBottomY, fillTopY);
                  if (shellBedrockY >= chunkMinY && shellBedrockY < chunkMaxY) {
                     if (sectionWriter != null) {
                        sectionWriter.setBlock(localX, localZ, shellBedrockY, BEDROCK_STATE);
                     } else {
                        cursor.set(worldX, shellBedrockY, worldZ);
                        MinecraftVersionCompat.setBlockState(chunk, cursor, BEDROCK_STATE);
                     }
                  }

                  int fillStartY = shellBedrockY + 1;
                  if (fillStartY <= fillTopY) {
                     if (this.settings.usesTerrainShell()) {
                        int columnDeepslateStart = TellusCaveDepthMapper.actualDeepslateBoundaryY(
                           surface, fillStartY, this.seaLevel
                        );
                        BlockState stoneFill = mountainMassFill != null ? mountainMassFill : STONE_STATE;
                        if (sectionWriter != null) {
                           sectionWriter.fillStoneColumnSpan(
                              localX, localZ, fillStartY, fillTopY, columnDeepslateStart, stoneFill, DEEPSLATE_STATE
                           );
                        } else {
                           for (int yx = fillStartY; yx <= fillTopY; yx++) {
                              cursor.set(worldX, yx, worldZ);
                              MinecraftVersionCompat.setBlockState(chunk, cursor, yx < columnDeepslateStart ? DEEPSLATE_STATE : stoneFill);
                           }
                        }
                     } else if (mountainMassFill != null) {
                        if (sectionWriter != null) {
                           sectionWriter.fillColumnConstant(localX, localZ, fillStartY, fillTopY, mountainMassFill);
                        } else {
                           for (int yx = fillStartY; yx <= fillTopY; yx++) {
                              cursor.set(worldX, yx, worldZ);
                              MinecraftVersionCompat.setBlockState(chunk, cursor, mountainMassFill);
                           }
                        }
                     } else if (oceanSupport && sectionWriter != null) {
                        sectionWriter.fillColumnConstant(localX, localZ, fillStartY, fillTopY, STONE_STATE);
                     } else if (oceanSupport) {
                        for (int yx = fillStartY; yx <= fillTopY; yx++) {
                           cursor.set(worldX, yx, worldZ);
                           MinecraftVersionCompat.setBlockState(chunk, cursor, STONE_STATE);
                        }
                     } else if (sectionWriter != null) {
                        sectionWriter.fillStoneColumnSpan(localX, localZ, fillStartY, fillTopY, deepslateStart, STONE_STATE, DEEPSLATE_STATE);
                     } else {
                        for (int yx = fillStartY; yx <= fillTopY; yx++) {
                           cursor.set(worldX, yx, worldZ);
                           MinecraftVersionCompat.setBlockState(chunk, cursor, yx < deepslateStart ? DEEPSLATE_STATE : STONE_STATE);
                        }
                     }
                  }
               } else {
                  if (mountainMassFill != null) {
                     int lowerTopY = Math.min(surface, deepslateStart - 1);
                     while (y <= lowerTopY) {
                        int sectionIndex = chunk.getSectionIndex(y);
                        if (sectionIndex >= 0 && sectionIndex < sectionCount && solidSections[sectionIndex]) {
                           y = sectionTopYs[sectionIndex] + 1;
                        } else {
                           int spanTopY = sectionIndex >= 0 && sectionIndex < sectionCount
                              ? Math.min(lowerTopY, sectionTopYs[sectionIndex])
                              : lowerTopY;
                           if (sectionWriter != null) {
                              sectionWriter.fillStoneColumnSpan(localX, localZ, y, spanTopY, deepslateStart, STONE_STATE, DEEPSLATE_STATE);
                           } else {
                              fillStoneColumnSpan(
                                 sections,
                                 columnFilledSections,
                                 chunkMinY,
                                 localX,
                                 localZ,
                                 y,
                                 spanTopY,
                                 deepslateStart,
                                 STONE_STATE,
                                 DEEPSLATE_STATE
                              );
                           }
                           y = spanTopY + 1;
                        }
                     }

                     int mountainStartY = Math.max(chunkMinY, deepslateStart);
                     if (mountainStartY <= surface) {
                        if (sectionWriter != null) {
                           sectionWriter.fillColumnConstant(localX, localZ, mountainStartY, surface, mountainMassFill);
                        } else {
                           fillColumnConstant(
                              sections, columnFilledSections, chunkMinY, localX, localZ, mountainStartY, surface, mountainMassFill
                           );
                        }
                     }
                  } else {
                     while (y <= surface) {
                        int sectionIndex = chunk.getSectionIndex(y);
                        if (sectionIndex >= 0 && sectionIndex < sectionCount && solidSections[sectionIndex]) {
                           y = sectionTopYs[sectionIndex] + 1;
                        } else {
                           int spanTopY = sectionIndex >= 0 && sectionIndex < sectionCount ? Math.min(surface, sectionTopYs[sectionIndex]) : surface;
                           if (sectionWriter != null) {
                              sectionWriter.fillStoneColumnSpan(localX, localZ, y, spanTopY, deepslateStart, STONE_STATE, DEEPSLATE_STATE);
                           } else {
                              fillStoneColumnSpan(
                                 sections,
                                 columnFilledSections,
                                 chunkMinY,
                                 localX,
                                 localZ,
                                 y,
                                 spanTopY,
                                 deepslateStart,
                                 STONE_STATE,
                                 DEEPSLATE_STATE
                              );
                           }
                           y = spanTopY + 1;
                        }
                     }
                  }

                  if (bedrockInChunk) {
                     if (sectionWriter != null) {
                        sectionWriter.setBlock(localX, localZ, bedrockY, BEDROCK_STATE);
                     } else {
                        cursor.set(worldX, bedrockY, worldZ);
                        MinecraftVersionCompat.setBlockState(chunk, cursor, BEDROCK_STATE);
                     }
                  }
               }
               columnStoneFillNs += elapsedFullChunkProfilingSince(subPhaseStartNs);

               subPhaseStartNs = beginFullChunkProfiling();
               if (hasWater && surface < waterSurface) {
                  if (sectionWriter != null) {
                     sectionWriter.fillColumnConstant(localX, localZ, surface + 1, waterSurface, WATER_STATE);
                  } else {
                     for (int yx = surface + 1; yx <= waterSurface; yx++) {
                        cursor.set(worldX, yx, worldZ);
                        MinecraftVersionCompat.setBlockState(chunk, cursor, WATER_STATE);
                     }
                  }
                  this.applyInlandFlowSurface(chunk, sectionWriter, cursor, worldX, worldZ, waterSurface);
                  if (this.isWaterfallSourceColumn(worldX, worldZ, waterSurface)) {
                     cursor.set(worldX, waterSurface, worldZ);
                     MinecraftVersionCompat.markPosForPostProcessing(chunk, cursor);
                  }
               }
               waterColumnFillNs += elapsedFullChunkProfilingSince(subPhaseStartNs);

               subPhaseStartNs = beginFullChunkProfiling();
               if (thinShellTerrain) {
                  if (sectionWriter != null) {
                     this.applyThinShellSurface(
                        sectionWriter,
                        worldX,
                        worldZ,
                        localX,
                        localZ,
                        surface,
                        chunkMinY,
                        underwater,
                        surfaceSnow,
                        biome,
                        slopeDiff,
                        convexity,
                        surfaceCoverClass,
                        oceanBeachCache,
                        surfaceProfiler,
                        useFastSurfacePalette
                     );
                  } else {
                     this.applyThinShellSurface(
                        chunk,
                        cursor,
                        worldX,
                        worldZ,
                        surface,
                        chunkMinY,
                        underwater,
                        surfaceSnow,
                        biome,
                        slopeDiff,
                        convexity,
                        surfaceCoverClass,
                        oceanBeachCache,
                        surfaceProfiler,
                        useFastSurfacePalette
                     );
                  }
               } else if (sectionWriter != null) {
                  this.applySurface(
                     sectionWriter,
                     worldX,
                     worldZ,
                     localX,
                     localZ,
                     surface,
                     chunkMinY,
                     underwater,
                     biome,
                     slopeDiff,
                     convexity,
                     surfaceCoverClass,
                     oceanBeachCache,
                     surfaceProfiler,
                     useFastSurfacePalette
                  );
               } else {
                  this.applySurface(
                     chunk,
                     cursor,
                     worldX,
                     worldZ,
                     surface,
                     chunkMinY,
                     underwater,
                     biome,
                     slopeDiff,
                     convexity,
                     surfaceCoverClass,
                     oceanBeachCache,
                     surfaceProfiler,
                     useFastSurfacePalette
                  );
               }
               surfaceApplyNs += elapsedFullChunkProfilingSince(subPhaseStartNs);

               if (surfaceSnow) {
                  subPhaseStartNs = beginFullChunkProfiling();
                  if (sectionWriter != null) {
                     this.applySnowCover(
                        sectionWriter, localX, localZ, worldX, worldZ, surface, chunkMinY, fullDepthSurfaceSnow, glacierHollow
                     );
                  } else {
                     this.applySnowCover(chunk, cursor, worldX, worldZ, surface, chunkMinY, fullDepthSurfaceSnow, glacierHollow);
                  }
                  snowApplyNs += elapsedFullChunkProfilingSince(subPhaseStartNs);
               }
            }
         }

         if (terrainShellBedrockSkinTopYs != null) {
            if (sectionWriter != null) {
               EarthChunkGenerator.ChunkSectionWriter bedrockWriter = sectionWriter;
               this.applyTerrainShellBedrockSkin(
                  pos,
                  terrainSurfaces,
                  terrainShellBedrockSkinTopYs,
                  terrainShellBedrockCurtainBottomYs,
                  chunkMinY,
                  (localX, localZ, worldX, worldZ, y) -> bedrockWriter.setBlock(localX, localZ, y, BEDROCK_STATE)
               );
            } else {
               this.applyTerrainShellBedrockSkin(
                  pos,
                  terrainSurfaces,
                  terrainShellBedrockSkinTopYs,
                  terrainShellBedrockCurtainBottomYs,
                  chunkMinY,
                  (localX, localZ, worldX, worldZ, y) -> {
                     cursor.set(worldX, y, worldZ);
                     MinecraftVersionCompat.setBlockState(chunk, cursor, BEDROCK_STATE);
                  }
               );
            }
         }

         if (sectionWriter != null) {
            EarthChunkGenerator.ChunkSectionWriter.FlushResult flushResult = sectionWriter.finish();
            solidSectionProfiler.recalcNs += flushResult.recalcNs();
            recordFullChunkProfilingCount(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_DIRTY_SECTIONS, flushResult.dirtySectionCount());
            sectionWriter = null;
         } else {
            long stoneRecalcStartNs = beginFullChunkProfiling();
            recalcFilledSections(sections, solidSections, columnFilledSections);
            columnStoneFillNs += elapsedFullChunkProfilingSince(stoneRecalcStartNs);
         }
      } finally {
         if (sectionWriter != null) {
            sectionWriter.close();
         }
      }

      recordFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SOLID_SECTIONS_MAX_INDEX, solidSectionProfiler.maxIndexNs);
      recordFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SOLID_SECTIONS_SCAN, solidSectionProfiler.scanNs);
      recordFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SOLID_SECTIONS_SECTION_ACCESS, solidSectionProfiler.sectionAccessNs);
      recordFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SOLID_SECTIONS_SECTION_WRITE, solidSectionProfiler.sectionWriteNs);
      recordFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SOLID_SECTIONS_RECALC, solidSectionProfiler.recalcNs);
      recordFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_STONE_FILL, columnStoneFillNs);
      recordFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_WATER_FILL, waterColumnFillNs);
      recordFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SURFACE_PALETTE, surfaceProfiler.paletteResolveNs);
      recordFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SURFACE_BADLANDS, surfaceProfiler.badlandsNs);
      recordFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SURFACE_BLOCK_WRITES, surfaceProfiler.blockWriteNs);
      recordFullChunkProfilingCount(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SURFACE_FAST_PATH, surfaceProfiler.fastPathCount);
      recordFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SURFACE, surfaceApplyNs);
      recordFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SNOW, snowApplyNs);
      endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS, phaseStartNs);

      if (thinShellTerrain) {
         this.applyUndergroundStructureProtection(structures, chunk, terrainSurfaces, false);
      }

      if (!terrainShellMode && !this.shouldDeferRoadDetails()) {
         phaseStartNs = beginFullChunkProfiling();
         this.applyOsmRoadOverlay(chunk, pos, terrainSurfaces, waterSurfaces, waterFlags, chunkMinY, chunkMaxY - 1, preparedBuildings);
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_ROADS, phaseStartNs);
      }

      if (!terrainShellMode) {
         phaseStartNs = beginFullChunkProfiling();
         this.carveStructureClearanceVolumes(structures, chunk);
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_STRUCTURE_CLEARANCE, phaseStartNs);
      }
   }

   private static void validateExperimentalChunkBounds(EarthGeneratorSettings settings, ChunkPos pos) {
      ExperimentalHeightSupport.validateHorizontalRangeOrThrow(
         settings,
         pos.getMinBlockX(),
         pos.getMaxBlockX(),
         pos.getMinBlockZ(),
         pos.getMaxBlockZ(),
         "chunk " + pos
      );
   }

   private OsmQueryMode resolveFullChunkOsmQueryMode() {
      return CHUNK_DETAIL_LEGACY_BLOCKING || !FULL_CHUNK_OSM_NON_BLOCKING ? OsmQueryMode.BLOCKING : OsmQueryMode.NON_BLOCKING;
   }

   private void applyOsmRoadOverlay(
      ChunkAccess chunk,
      ChunkPos pos,
      int[] terrainSurfaces,
      int[] waterSurfaces,
      boolean[] waterFlags,
      int chunkMinY,
      int chunkMaxY,
      EarthChunkGenerator.PreparedChunkBuildings preparedBuildings
   ) {
      this.applyOsmRoadOverlay(null, chunk, pos, terrainSurfaces, waterSurfaces, waterFlags, chunkMinY, chunkMaxY, preparedBuildings, null);
   }

   private void applyOsmRoadOverlay(
      WorldGenLevel level,
      ChunkAccess chunk,
      ChunkPos pos,
      int[] terrainSurfaces,
      int[] waterSurfaces,
      boolean[] waterFlags,
      int chunkMinY,
      int chunkMaxY,
      EarthChunkGenerator.PreparedChunkBuildings preparedBuildings,
      EarthChunkGenerator.OsmRoadQueryResult deferredRoadQuery
      ) {
      long chunkKey = MinecraftVersionCompat.packChunkPos(MinecraftVersionCompat.chunkX(pos), MinecraftVersionCompat.chunkZ(pos));
      this.preparedChunkRoadLights.remove(chunkKey);
      this.clearPreparedChunkStateTracking(chunkKey);
      if (this.settings.enableRoads()) {
         double worldScale = this.settings.worldScale();
         if (!(worldScale <= 0.0) && !(worldScale > OSM_ROAD_MAX_SCALE)) {
            int chunkMinX = pos.getMinBlockX();
            int chunkMinZ = pos.getMinBlockZ();
            int chunkMaxX = chunkMinX + CHUNK_MASK;
            int chunkMaxZ = chunkMinZ + CHUNK_MASK;
            OsmQueryMode queryMode = deferredRoadQuery == null ? this.resolveFullChunkOsmQueryMode() : OsmQueryMode.BLOCKING;
            long fetchStartNs = deferredRoadQuery == null ? OsmPerf.now() : 0L;
            EarthChunkGenerator.OsmRoadQueryResult roadQuery = deferredRoadQuery != null
               ? deferredRoadQuery
               : this.fetchOsmRoadsForAreaDetailed(chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ, OSM_ROAD_QUERY_MARGIN, queryMode);
            if (deferredRoadQuery == null && queryMode == OsmQueryMode.NON_BLOCKING && roadQuery.hadCacheMisses() && roadQuery.features().isEmpty()) {
               EarthChunkGenerator.ChunkDetailPerf.recordSkippedBlockingFallback();
            }

            List<RoadFeature> roads = roadQuery.features();
            long fetchNs = OsmPerf.elapsedSince(fetchStartNs);
            if (roads.isEmpty()) {
               OsmPerf.recordFullChunkRoad(fetchNs, 0L, 0);
            } else {
               long rasterStartNs = OsmPerf.now();
               EarthChunkGenerator.RoadWidths widths = resolveRoadWidths(worldScale);
               List<RoadFeature> mainRoads = new ArrayList<>();
               List<RoadFeature> mainBridgeRoads = new ArrayList<>();
               List<RoadFeature> normalRoads = new ArrayList<>();
               List<RoadFeature> normalBridgeRoads = new ArrayList<>();
               List<RoadFeature> dirtRoads = new ArrayList<>();
               List<RoadFeature> dirtBridgeRoads = new ArrayList<>();

               for (RoadFeature road : roads) {
                  switch (road.roadClass()) {
                     case MAIN:
                        mainRoads.add(road);
                        if (road.mode() == RoadMode.BRIDGE) {
                           mainBridgeRoads.add(road);
                        }
                        break;
                     case NORMAL:
                        normalRoads.add(road);
                        if (road.mode() == RoadMode.BRIDGE) {
                           normalBridgeRoads.add(road);
                        }
                        break;
                     case DIRT:
                        dirtRoads.add(road);
                        if (road.mode() == RoadMode.BRIDGE) {
                           dirtBridgeRoads.add(road);
                        }
                  }
               }

               if (mainRoads.isEmpty() && normalRoads.isEmpty() && dirtRoads.isEmpty()) {
                  OsmPerf.recordFullChunkRoad(fetchNs, OsmPerf.elapsedSince(rasterStartNs), roads.size());
               } else {
                  int padding = 0;
                  int extSide = CHUNK_SIDE + padding * 2;
                  int extArea = extSide * extSide;
                  int extMinX = chunkMinX - padding;
                  int extMinZ = chunkMinZ - padding;
                  EarthChunkGenerator.OsmOverlayScratch scratch = this.osmOverlayScratch.get();
                  scratch.ensureRoadExtCapacity(extArea);
                  byte[] resolvedClass = scratch.resolvedClass;
                  byte[] resolvedMode = scratch.resolvedMode;
                  byte[] resolvedStyle = scratch.resolvedStyle;
                  boolean[] resolvedMarking = scratch.resolvedMarking;
                  int[] resolvedDeckY = scratch.resolvedDeckY;
                  boolean[] resolvedTunnelCarve = scratch.resolvedTunnelCarve;
                  boolean[] blockedByHigherClass = scratch.blockedByHigherClass;
                  boolean[] bridgeOverlayPresent = scratch.bridgeOverlayPresent;
                  int[] bridgeOverlayDeckY = scratch.bridgeOverlayDeckY;
                  byte[] bridgeOverlayClass = scratch.bridgeOverlayClass;
                  byte[] bridgeOverlayStyle = scratch.bridgeOverlayStyle;
                  boolean[] bridgeOverlayMarking = scratch.bridgeOverlayMarking;
                  boolean[] bridgeSupportShaftPresent = scratch.bridgeSupportShaftPresent;
                  int[] bridgeSupportShaftBottomY = scratch.bridgeSupportShaftBottomY;
                  int[] bridgeSupportShaftTopY = scratch.bridgeSupportShaftTopY;
                  boolean[] bridgeSupportCapPresent = scratch.bridgeSupportCapPresent;
                  int[] bridgeSupportCapBottomY = scratch.bridgeSupportCapBottomY;
                  int[] bridgeSupportCapTopY = scratch.bridgeSupportCapTopY;
                  scratch.clearRoadExtState(extArea);
                  double blocksPerDegree = blocksPerDegree(worldScale);
                  Long2ObjectOpenHashMap<EarthChunkGenerator.RoadColumnSample> edgeColumnCache = scratch.edgeColumnCache;
                  edgeColumnCache.clear();
                  List<OsmStreetLightFeature> crossings = filterRoadPointsByKind(
                     this.fetchOsmRoadPointsForAreaDetailed(chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ, OSM_CROSSING_QUERY_MARGIN, queryMode).features(),
                     RoadPointKind.CROSSING
                  );
                  this.rasterizeRoadClassPass(
                     mainRoads,
                     crossings,
                     RoadClass.MAIN,
                     widths.main(),
                     blocksPerDegree,
                     extMinX,
                     extMinZ,
                     extSide,
                     chunkMinX,
                     chunkMinZ,
                     terrainSurfaces,
                     waterSurfaces,
                     waterFlags,
                     chunkMinY,
                     chunkMaxY,
                     edgeColumnCache,
                     resolvedClass,
                     resolvedMode,
                     resolvedStyle,
                     resolvedMarking,
                     resolvedDeckY,
                     resolvedTunnelCarve,
                     blockedByHigherClass,
                     bridgeOverlayPresent,
                     bridgeOverlayDeckY,
                     bridgeOverlayClass,
                     bridgeOverlayStyle,
                     bridgeOverlayMarking,
                     scratch,
                     extArea
                  );
                  this.rasterizeRoadClassPass(
                     normalRoads,
                     crossings,
                     RoadClass.NORMAL,
                     widths.normal(),
                     blocksPerDegree,
                     extMinX,
                     extMinZ,
                     extSide,
                     chunkMinX,
                     chunkMinZ,
                     terrainSurfaces,
                     waterSurfaces,
                     waterFlags,
                     chunkMinY,
                     chunkMaxY,
                     edgeColumnCache,
                     resolvedClass,
                     resolvedMode,
                     resolvedStyle,
                     resolvedMarking,
                     resolvedDeckY,
                     resolvedTunnelCarve,
                     blockedByHigherClass,
                     bridgeOverlayPresent,
                     bridgeOverlayDeckY,
                     bridgeOverlayClass,
                     bridgeOverlayStyle,
                     bridgeOverlayMarking,
                     scratch,
                     extArea
                  );
                  this.rasterizeRoadClassPass(
                     dirtRoads,
                     crossings,
                     RoadClass.DIRT,
                     widths.dirt(),
                     blocksPerDegree,
                     extMinX,
                     extMinZ,
                     extSide,
                     chunkMinX,
                     chunkMinZ,
                     terrainSurfaces,
                     waterSurfaces,
                     waterFlags,
                     chunkMinY,
                     chunkMaxY,
                     edgeColumnCache,
                     resolvedClass,
                     resolvedMode,
                     resolvedStyle,
                     resolvedMarking,
                     resolvedDeckY,
                     resolvedTunnelCarve,
                     blockedByHigherClass,
                     bridgeOverlayPresent,
                     bridgeOverlayDeckY,
                     bridgeOverlayClass,
                     bridgeOverlayStyle,
                     bridgeOverlayMarking,
                     scratch,
                     extArea
                  );
                  this.rasterizeBridgeSupports(
                     mainBridgeRoads,
                     widths.main(),
                     blocksPerDegree,
                     extMinX,
                     extMinZ,
                     extSide,
                     chunkMinX,
                     chunkMinZ,
                     terrainSurfaces,
                     waterSurfaces,
                     waterFlags,
                     chunkMinY,
                     chunkMaxY,
                     edgeColumnCache,
                     resolvedClass,
                     resolvedDeckY,
                     bridgeOverlayPresent,
                     bridgeOverlayDeckY,
                     preparedBuildings,
                     bridgeSupportShaftPresent,
                     bridgeSupportShaftBottomY,
                     bridgeSupportShaftTopY,
                     bridgeSupportCapPresent,
                     bridgeSupportCapBottomY,
                     bridgeSupportCapTopY
                  );
                  this.rasterizeBridgeSupports(
                     normalBridgeRoads,
                     widths.normal(),
                     blocksPerDegree,
                     extMinX,
                     extMinZ,
                     extSide,
                     chunkMinX,
                     chunkMinZ,
                     terrainSurfaces,
                     waterSurfaces,
                     waterFlags,
                     chunkMinY,
                     chunkMaxY,
                     edgeColumnCache,
                     resolvedClass,
                     resolvedDeckY,
                     bridgeOverlayPresent,
                     bridgeOverlayDeckY,
                     preparedBuildings,
                     bridgeSupportShaftPresent,
                     bridgeSupportShaftBottomY,
                     bridgeSupportShaftTopY,
                     bridgeSupportCapPresent,
                     bridgeSupportCapBottomY,
                     bridgeSupportCapTopY
                  );
                  this.rasterizeBridgeSupports(
                     dirtBridgeRoads,
                     widths.dirt(),
                     blocksPerDegree,
                     extMinX,
                     extMinZ,
                     extSide,
                     chunkMinX,
                     chunkMinZ,
                     terrainSurfaces,
                     waterSurfaces,
                     waterFlags,
                     chunkMinY,
                     chunkMaxY,
                     edgeColumnCache,
                     resolvedClass,
                     resolvedDeckY,
                     bridgeOverlayPresent,
                     bridgeOverlayDeckY,
                     preparedBuildings,
                     bridgeSupportShaftPresent,
                     bridgeSupportShaftBottomY,
                     bridgeSupportShaftTopY,
                     bridgeSupportCapPresent,
                     bridgeSupportCapBottomY,
                     bridgeSupportCapTopY
                  );
                  byte[] chunkRoadClass = scratch.chunkRoadClass;
                  byte[] chunkRoadMode = scratch.chunkRoadMode;
                  byte[] chunkRoadStyle = scratch.chunkRoadStyle;
                  boolean[] chunkRoadMarking = scratch.chunkRoadMarking;
                  int[] chunkRoadDeckY = scratch.chunkRoadDeckY;
                  boolean[] chunkTunnelNeedsCarve = scratch.chunkTunnelNeedsCarve;
                  byte bridgeModeId = (byte)(RoadMode.BRIDGE.ordinal() + 1);
                  Arrays.fill(chunkRoadClass, (byte)0);
                  Arrays.fill(chunkRoadMode, (byte)0);
                  Arrays.fill(chunkRoadStyle, (byte)0);
                  Arrays.fill(chunkRoadMarking, false);
                  Arrays.fill(chunkRoadDeckY, 0);
                  Arrays.fill(chunkTunnelNeedsCarve, false);
                  MutableBlockPos cursor = new MutableBlockPos();
                  byte tunnelModeId = (byte)(RoadMode.TUNNEL.ordinal() + 1);

                  for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
                     for (int localX = 0; localX < CHUNK_SIDE; localX++) {
                        int extIndex = extIndex(localX + padding, localZ + padding, extSide);
                        int classId = resolvedClass[extIndex];
                        if (classId > 0) {
                           int deckY = Mth.clamp(resolvedDeckY[extIndex], chunkMinY, chunkMaxY);
                           if (preparedBuildings != null && preparedBuildings.intersectsRoad(localX, localZ, deckY)) {
                              continue;
                           }

                           int worldX = chunkMinX + localX;
                           int worldZ = chunkMinZ + localZ;
                           if (resolvedMode[extIndex] != tunnelModeId) {
                              this.clearRoadSurfaceColumn(level, chunk, cursor, worldX, worldZ, deckY, terrainSurfaces[chunkIndex(localX, localZ)], chunkMinY, chunkMaxY);
                           }

                           cursor.set(worldX, deckY, worldZ);
                           this.setChunkBlock(level, chunk, cursor, roadStateForStyle(classId, resolvedStyle[extIndex], resolvedMarking[extIndex]));
                           int chunkIndex = chunkIndex(localX, localZ);
                           chunkRoadClass[chunkIndex] = (byte)classId;
                           chunkRoadMode[chunkIndex] = resolvedMode[extIndex];
                           chunkRoadStyle[chunkIndex] = resolvedStyle[extIndex];
                           chunkRoadMarking[chunkIndex] = resolvedMarking[extIndex];
                           chunkRoadDeckY[chunkIndex] = deckY;
                           chunkTunnelNeedsCarve[chunkIndex] = resolvedTunnelCarve[extIndex];
                        }
                     }
                  }

                  for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
                     for (int localXx = 0; localXx < CHUNK_SIDE; localXx++) {
                        int extIndex = extIndex(localXx + padding, localZ + padding, extSide);
                        if (bridgeOverlayPresent[extIndex]) {
                           int classId = bridgeOverlayClass[extIndex];
                           if (classId > 0) {
                              int deckY = Mth.clamp(bridgeOverlayDeckY[extIndex], chunkMinY, chunkMaxY);
                              if (preparedBuildings != null && preparedBuildings.intersectsRoad(localXx, localZ, deckY)) {
                                 continue;
                              }

                              int worldX = chunkMinX + localXx;
                              int worldZ = chunkMinZ + localZ;
                              this.clearRoadSurfaceColumn(level, chunk, cursor, worldX, worldZ, deckY, terrainSurfaces[chunkIndex(localXx, localZ)], chunkMinY, chunkMaxY);
                              cursor.set(worldX, deckY, worldZ);
                              this.setChunkBlock(level, chunk, cursor, roadStateForStyle(classId, bridgeOverlayStyle[extIndex], bridgeOverlayMarking[extIndex]));
                              int chunkIndex = chunkIndex(localXx, localZ);
                              chunkRoadClass[chunkIndex] = (byte)classId;
                              chunkRoadMode[chunkIndex] = bridgeModeId;
                              chunkRoadStyle[chunkIndex] = bridgeOverlayStyle[extIndex];
                              chunkRoadMarking[chunkIndex] = bridgeOverlayMarking[extIndex];
                              chunkRoadDeckY[chunkIndex] = deckY;
                              chunkTunnelNeedsCarve[chunkIndex] = false;
                           }
                        }
                     }
                  }

                  for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
                     for (int localX = 0; localX < CHUNK_SIDE; localX++) {
                        int extIndex = extIndex(localX + padding, localZ + padding, extSide);
                        int worldX = chunkMinX + localX;
                        int worldZ = chunkMinZ + localZ;
                        if (bridgeSupportShaftPresent[extIndex]) {
                           int bottomY = Mth.clamp(bridgeSupportShaftBottomY[extIndex], chunkMinY, chunkMaxY);
                           int topY = Mth.clamp(bridgeSupportShaftTopY[extIndex], chunkMinY, chunkMaxY);
                           if (topY >= bottomY) {
                              for (int y = bottomY; y <= topY; y++) {
                                 cursor.set(worldX, y, worldZ);
                                 this.setChunkBlock(level, chunk, cursor, BRIDGE_SUPPORT_SHAFT_STATE);
                              }
                           }
                        }

                        if (bridgeSupportCapPresent[extIndex]) {
                           int bottomY = Mth.clamp(bridgeSupportCapBottomY[extIndex], chunkMinY, chunkMaxY);
                           int topY = Mth.clamp(bridgeSupportCapTopY[extIndex], chunkMinY, chunkMaxY);
                           if (topY >= bottomY) {
                              for (int y = bottomY; y <= topY; y++) {
                                 cursor.set(worldX, y, worldZ);
                                 this.setChunkBlock(level, chunk, cursor, BRIDGE_SUPPORT_CAP_STATE);
                              }
                           }
                        }
                     }
                  }

                  int[] classWidths = scratch.classWidths;
                  classWidths[0] = 0;
                  classWidths[1] = widths.main();
                  classWidths[2] = widths.normal();
                  classWidths[3] = widths.dirt();
                  boolean[] tunnelCarveMask = scratch.tunnelCarveMask;
                  int[] tunnelCarveDeckY = scratch.tunnelCarveDeckY;
                  Arrays.fill(tunnelCarveMask, false);
                  Arrays.fill(tunnelCarveDeckY, Integer.MIN_VALUE);

                  for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
                     for (int localXxx = 0; localXxx < CHUNK_SIDE; localXxx++) {
                        int centerIndex = chunkIndex(localXxx, localZ);
                        if (chunkRoadClass[centerIndex] > 0 && chunkRoadMode[centerIndex] == tunnelModeId && chunkTunnelNeedsCarve[centerIndex]) {
                           int roadWidth = classWidths[chunkRoadClass[centerIndex]];
                           int carveWidth = roadWidth + OSM_TUNNEL_SIDE_CLEARANCE * 2;
                           double carveRadius = Math.max(0.5, (carveWidth - 1) * 0.5);
                           int radius = Mth.ceil(carveRadius);
                           double carveRadiusSq = carveRadius * carveRadius + 1.0E-6;

                           for (int dz = -radius; dz <= radius; dz++) {
                              int targetZ = localZ + dz;
                              if (targetZ >= 0 && targetZ < CHUNK_SIDE) {
                                 for (int dx = -radius; dx <= radius; dx++) {
                                    int targetX = localXxx + dx;
                                    if (targetX >= 0 && targetX < CHUNK_SIDE) {
                                       double distSq = dx * dx + dz * dz;
                                       if (!(distSq > carveRadiusSq)) {
                                          int targetIndex = chunkIndex(targetX, targetZ);
                                          tunnelCarveMask[targetIndex] = true;
                                          tunnelCarveDeckY[targetIndex] = Math.max(tunnelCarveDeckY[targetIndex], chunkRoadDeckY[centerIndex]);
                                       }
                                    }
                                 }
                              }
                           }
                        }
                     }
                  }

                  for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
                     int worldZ = chunkMinZ + localZ;

                     for (int localXxxx = 0; localXxxx < CHUNK_SIDE; localXxxx++) {
                        int chunkIndex = chunkIndex(localXxxx, localZ);
                        if (tunnelCarveMask[chunkIndex]) {
                           int deckY = tunnelCarveDeckY[chunkIndex];
                           if (deckY >= chunkMinY && deckY < chunkMaxY) {
                              int topY = Math.min(chunkMaxY, deckY + OSM_TUNNEL_INTERNAL_HEIGHT);
                              if (topY > deckY) {
                                 int worldX = chunkMinX + localXxxx;
                                 boolean intersectsSolid = false;

                                 for (int y = deckY + 1; y <= topY; y++) {
                                    cursor.set(worldX, y, worldZ);
                                    if (isTunnelCarveReplaceable(chunk.getBlockState(cursor))) {
                                       intersectsSolid = true;
                                       break;
                                    }
                                 }

                                 if (intersectsSolid) {
                                    for (int yx = deckY + 1; yx <= topY; yx++) {
                                       cursor.set(worldX, yx, worldZ);
                                       if (isTunnelCarveReplaceable(chunk.getBlockState(cursor))) {
                                          this.setChunkBlock(level, chunk, cursor, CAVE_AIR_STATE);
                                       }
                                    }
                                 }
                              }
                           }
                        }
                     }
                  }

                  List<RoadAreaFeature> roadAreas = this.fetchOsmRoadAreasForAreaDetailed(
                     chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ, 8, queryMode
                  ).features();
                  this.applyRoadAreaOverlay(
                     level,
                     chunk,
                     cursor,
                     chunkMinX,
                     chunkMinZ,
                     chunkMinY,
                     chunkMaxY,
                     roadAreas,
                     terrainSurfaces,
                     waterSurfaces,
                     waterFlags,
                     chunkRoadClass,
                     preparedBuildings
                  );

                  EarthChunkGenerator.OsmStreetLightQueryResult roadPointQuery = this.fetchOsmRoadPointsForAreaDetailed(
                     chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ, 8, queryMode
                  );
                  List<OsmStreetLightFeature> roadPoints = roadPointQuery.features();
                  this.applyRoadPointDecorations(
                     level,
                     chunk,
                     cursor,
                     chunkMinX,
                     chunkMinZ,
                     chunkMinY,
                     chunkMaxY,
                     roadPoints,
                     chunkRoadClass,
                     chunkRoadMode,
                     chunkRoadDeckY,
                     preparedBuildings
                  );
                  EarthChunkGenerator.PreparedChunkRoadLights preparedRoadLights = this.prepareRoadLightsForChunk(
                     pos,
                     roads,
                     this.settings.worldScale() <= StreetLightPlanner.MAX_SCALE
                        ? this.fetchOsmStreetLightsForAreaDetailed(chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ,
                           StreetLightPlanner.CONTEXT_MARGIN, queryMode).features() : List.of(),
                     widths,
                     chunkRoadClass,
                     terrainSurfaces,
                     waterSurfaces,
                     waterFlags,
                     bridgeSupportShaftPresent,
                     bridgeSupportShaftBottomY,
                     bridgeSupportShaftTopY,
                     bridgeSupportCapPresent,
                     bridgeSupportCapBottomY,
                     bridgeSupportCapTopY,
                     preparedBuildings
                  );
                  if (preparedRoadLights != null && !preparedRoadLights.isEmpty()) {
                     this.preparedChunkRoadLights.put(chunkKey, preparedRoadLights);
                     this.markPreparedChunkState(chunkKey);
                  }

                  OsmPerf.recordFullChunkRoad(fetchNs, OsmPerf.elapsedSince(rasterStartNs), roads.size());
               }
            }
         }
      }
   }

   private void rasterizeRoadClassPass(
      List<RoadFeature> roads,
      List<OsmStreetLightFeature> crossings,
      RoadClass roadClass,
      int roadWidth,
      double blocksPerDegree,
      int extMinX,
      int extMinZ,
      int extSide,
      int chunkMinX,
      int chunkMinZ,
      int[] terrainSurfaces,
      int[] waterSurfaces,
      boolean[] waterFlags,
      int chunkMinY,
      int chunkMaxY,
      Long2ObjectOpenHashMap<EarthChunkGenerator.RoadColumnSample> edgeColumnCache,
      byte[] resolvedClass,
      byte[] resolvedMode,
      byte[] resolvedStyle,
      boolean[] resolvedMarking,
      int[] resolvedDeckY,
      boolean[] resolvedTunnelCarve,
      boolean[] blockedByHigherClass,
      boolean[] bridgeOverlayPresent,
      int[] bridgeOverlayDeckY,
      byte[] bridgeOverlayClass,
      byte[] bridgeOverlayStyle,
      boolean[] bridgeOverlayMarking,
      EarthChunkGenerator.OsmOverlayScratch scratch,
      int extArea
   ) {
      if (!roads.isEmpty() && roadWidth > 0) {
         boolean[] candidatePresent = scratch.candidatePresent;
         int[] candidateDeckY = scratch.candidateDeckY;
         byte[] candidateMode = scratch.candidateMode;
         byte[] candidateStyle = scratch.candidateStyle;
         boolean[] candidateMarking = scratch.candidateMarking;
         boolean[] candidateTunnelCarve = scratch.candidateTunnelCarve;
         boolean[] bridgeCandidatePresent = scratch.bridgeCandidatePresent;
         int[] bridgeCandidateDeckY = scratch.bridgeCandidateDeckY;
         byte[] bridgeCandidateStyle = scratch.bridgeCandidateStyle;
         boolean[] bridgeCandidateMarking = scratch.bridgeCandidateMarking;
         scratch.clearRoadCandidateState(extArea);

         for (RoadFeature road : roads) {
            int featureRoadWidth = RoadSurfaceStyle.effectiveRoadWidth(road, roadWidth, EarthProjection.worldScaleFromBlocksPerDegree(blocksPerDegree));
            if (road.mode() == RoadMode.BRIDGE) {
               this.rasterizeRoadFeature(
                  road,
                  crossings,
                  featureRoadWidth,
                  blocksPerDegree,
                  extMinX,
                  extMinZ,
                  extSide,
                  chunkMinX,
                  chunkMinZ,
                  terrainSurfaces,
                  waterSurfaces,
                  waterFlags,
                  chunkMinY,
                  chunkMaxY,
                  edgeColumnCache,
                  bridgeCandidatePresent,
                  bridgeCandidateDeckY,
                  bridgeCandidateStyle,
                  bridgeCandidateMarking,
                  null,
                  null
               );
            } else {
               this.rasterizeRoadFeature(
                  road,
                  crossings,
                  featureRoadWidth,
                  blocksPerDegree,
                  extMinX,
                  extMinZ,
                  extSide,
                  chunkMinX,
                  chunkMinZ,
                  terrainSurfaces,
                  waterSurfaces,
                  waterFlags,
                  chunkMinY,
                  chunkMaxY,
                  edgeColumnCache,
                  candidatePresent,
                  candidateDeckY,
                  candidateStyle,
                  candidateMarking,
                  candidateMode,
                  candidateTunnelCarve
               );
            }
         }

         int classId = roadClass.ordinal() + 1;

         for (int index = 0; index < extArea; index++) {
            if (bridgeCandidatePresent[index]) {
               mergeBridgeOverlay(
                  index,
                  classId,
                  bridgeCandidateDeckY[index],
                  bridgeCandidateStyle[index],
                  bridgeCandidateMarking[index],
                  bridgeOverlayPresent,
                  bridgeOverlayDeckY,
                  bridgeOverlayClass,
                  bridgeOverlayStyle,
                  bridgeOverlayMarking
               );
            }
         }

         int[] placed = scratch.placed;
         int placedCount = 0;

         for (int indexx = 0; indexx < extArea; indexx++) {
            if (candidatePresent[indexx] && !blockedByHigherClass[indexx]) {
               resolvedClass[indexx] = (byte)classId;
               resolvedMode[indexx] = candidateMode[indexx];
               resolvedStyle[indexx] = candidateStyle[indexx];
               resolvedMarking[indexx] = candidateMarking[indexx];
               resolvedDeckY[indexx] = candidateDeckY[indexx];
               resolvedTunnelCarve[indexx] = candidateTunnelCarve[indexx];
               placed[placedCount++] = indexx;
            }
         }

         for (int i = 0; i < placedCount; i++) {
            int indexxx = placed[i];
            int localX = indexxx % extSide;
            int localZ = indexxx / extSide;

            for (int dz = -OSM_ROAD_CLASS_SEPARATION; dz <= OSM_ROAD_CLASS_SEPARATION; dz++) {
               int z = localZ + dz;
               if (z >= 0 && z < extSide) {
                  for (int dx = -OSM_ROAD_CLASS_SEPARATION; dx <= OSM_ROAD_CLASS_SEPARATION; dx++) {
                     int x = localX + dx;
                     if (x >= 0 && x < extSide) {
                        blockedByHigherClass[extIndex(x, z, extSide)] = true;
                     }
                  }
               }
            }
         }
      }
   }

   private void rasterizeRoadFeature(
      RoadFeature road,
      List<OsmStreetLightFeature> crossings,
      int roadWidth,
      double blocksPerDegree,
      int extMinX,
      int extMinZ,
      int extSide,
      int chunkMinX,
      int chunkMinZ,
      int[] terrainSurfaces,
      int[] waterSurfaces,
      boolean[] waterFlags,
      int chunkMinY,
      int chunkMaxY,
      Long2ObjectOpenHashMap<EarthChunkGenerator.RoadColumnSample> edgeColumnCache,
      boolean[] candidatePresent,
      int[] candidateDeckY,
      byte[] candidateStyle,
      boolean[] candidateMarking,
      byte[] candidateMode,
      boolean[] candidateTunnelCarve
   ) {
      int pointCount = road.pointCount();
      if (pointCount >= 2) {
         double worldScale = EarthProjection.worldScaleFromBlocksPerDegree(blocksPerDegree);
         double startWorldX = road.lonAt(0) * blocksPerDegree;
         double startWorldZ = EarthProjection.latToBlockZ(road.latAt(0), worldScale);
         double previousX = startWorldX;
         double previousZ = startWorldZ;
         double endWorldX = startWorldX;
         double endWorldZ = startWorldZ;
         double totalLength = 0.0;

         for (int i = 1; i < pointCount; i++) {
            double currentX = road.lonAt(i) * blocksPerDegree;
            double currentZ = EarthProjection.latToBlockZ(road.latAt(i), worldScale);
            double dx = currentX - previousX;
            double dz = currentZ - previousZ;
            double segmentLength = Math.sqrt(dx * dx + dz * dz);
            totalLength += segmentLength;
            previousX = currentX;
            previousZ = currentZ;
            endWorldX = currentX;
            endWorldZ = currentZ;
         }

         if (!(totalLength <= 1.0E-6)) {
            int tunnelStartSurface = 0;
            int tunnelEndSurface = 0;
            int bridgeStartSurface = 0;
            int bridgeEndSurface = 0;
            if (road.mode() == RoadMode.TUNNEL || road.mode() == RoadMode.BRIDGE) {
               EarthChunkGenerator.RoadColumnSample start = this.sampleRoadColumnForOverlay(
                  Mth.floor(startWorldX), Mth.floor(startWorldZ), chunkMinX, chunkMinZ, terrainSurfaces, waterSurfaces, waterFlags, edgeColumnCache
               );
               EarthChunkGenerator.RoadColumnSample end = this.sampleRoadColumnForOverlay(
                  Mth.floor(endWorldX), Mth.floor(endWorldZ), chunkMinX, chunkMinZ, terrainSurfaces, waterSurfaces, waterFlags, edgeColumnCache
               );
               if (road.mode() == RoadMode.TUNNEL) {
                  tunnelStartSurface = start.roadSurface();
                  tunnelEndSurface = end.roadSurface();
               } else {
                  bridgeStartSurface = start.roadSurface();
                  bridgeEndSurface = end.roadSurface();
               }
            }

            double halfWidth = Math.max(0.5, (roadWidth - 1) * 0.5);
            double radiusSq = halfWidth * halfWidth + 1.0E-6;
            double[] crossingStations = crossingStationsOnRoad(road, roadWidth, crossings, blocksPerDegree, worldScale);
            double segmentStart = 0.0;
            double x1 = startWorldX;
            double z1 = startWorldZ;

            for (int i = 1; i < pointCount; i++) {
               double x2 = road.lonAt(i) * blocksPerDegree;
               double z2 = EarthProjection.latToBlockZ(road.latAt(i), worldScale);
               double dx = x2 - x1;
               double dz = z2 - z1;
               double segmentLengthSq = dx * dx + dz * dz;
               if (segmentLengthSq <= 1.0E-6) {
                  x1 = x2;
                  z1 = z2;
               } else {
                  double segmentLength = Math.sqrt(segmentLengthSq);
                  int minX = Math.max(extMinX, Mth.floor(Math.min(x1, x2) - halfWidth - 1.0));
                  int maxX = Math.min(extMinX + extSide - 1, Mth.floor(Math.max(x1, x2) + halfWidth + 1.0));
                  int minZ = Math.max(extMinZ, Mth.floor(Math.min(z1, z2) - halfWidth - 1.0));
                  int maxZ = Math.min(extMinZ + extSide - 1, Mth.floor(Math.max(z1, z2) + halfWidth + 1.0));

                  for (int worldCellZ = minZ; worldCellZ <= maxZ; worldCellZ++) {
                     for (int worldCellX = minX; worldCellX <= maxX; worldCellX++) {
                        double t = ((worldCellX - x1) * dx + (worldCellZ - z1) * dz) / segmentLengthSq;
                        t = Mth.clamp(t, 0.0, 1.0);
                        double projectionX = x1 + t * dx;
                        double projectionZ = z1 + t * dz;
                        double distX = worldCellX - projectionX;
                        double distZ = worldCellZ - projectionZ;
                        double distanceSq = distX * distX + distZ * distZ;
                        if (!(distanceSq > radiusSq)) {
                           double station = segmentStart + t * segmentLength;
                           EarthChunkGenerator.RoadColumnSample centerColumn = this.sampleRoadColumnForOverlay(
                              Mth.floor(projectionX), Mth.floor(projectionZ), chunkMinX, chunkMinZ, terrainSurfaces, waterSurfaces, waterFlags, edgeColumnCache
                           );
                           EarthChunkGenerator.RoadColumnSample column = this.sampleRoadColumnForOverlay(
                              worldCellX, worldCellZ, chunkMinX, chunkMinZ, terrainSurfaces, waterSurfaces, waterFlags, edgeColumnCache
                           );
                           boolean tunnelNeedsCarve = false;
                           int deckY;
                           if (road.mode() == RoadMode.BRIDGE) {
                              deckY = bridgeDeckYAtStation(
                                 station,
                                 totalLength,
                                 bridgeStartSurface,
                                 bridgeEndSurface,
                                 column,
                                 road.bridgeLevel(),
                                 road.roadClass(),
                                 worldScale
                              );
                           } else if (road.mode() == RoadMode.TUNNEL) {
                              int requestedDeckY = tunnelDeckAtStation(station, totalLength, tunnelStartSurface, tunnelEndSurface);
                              requestedDeckY = Math.min(requestedDeckY, column.roadSurface());
                              tunnelNeedsCarve = column.terrainSurface() > requestedDeckY + 1;
                              deckY = tunnelNeedsCarve ? requestedDeckY : column.roadSurface();
                           } else {
                              deckY = normalRoadDeckYAtCell(centerColumn.roadSurface(), column.roadSurface(), Math.sqrt(distanceSq), halfWidth);
                           }

                           deckY = Mth.clamp(deckY, chunkMinY, chunkMaxY);
                           int extIndex = extIndex(worldCellX - extMinX, worldCellZ - extMinZ, extSide);
                           boolean replaceCandidate = candidateMode == null || candidateTunnelCarve == null
                              ? shouldReplaceBridgeCandidate(candidatePresent[extIndex], candidateDeckY[extIndex], deckY)
                              : shouldReplaceRoadCandidate(
                                 candidatePresent[extIndex],
                                 candidateDeckY[extIndex],
                                 candidateMode[extIndex],
                                 candidateTunnelCarve[extIndex],
                                 deckY,
                                 road.mode(),
                                 tunnelNeedsCarve
                              );
                           if (replaceCandidate) {
                              candidatePresent[extIndex] = true;
                              candidateDeckY[extIndex] = deckY;
                              if (candidateStyle != null && candidateMarking != null) {
                                 candidateStyle[extIndex] = RoadSurfaceStyle.surfaceStyleId(road, worldCellX, worldCellZ);
                                 double lateralDistance = ((worldCellX - projectionX) * -dz + (worldCellZ - projectionZ) * dx) / segmentLength;
                                 candidateMarking[extIndex] = RoadCrossingLayout.marking(
                                    crossingStations,
                                    station,
                                    lateralDistance,
                                    dx / segmentLength,
                                    dz / segmentLength,
                                    RoadSurfaceStyle.shouldDrawLaneMarking(road, roadWidth, station, lateralDistance, Math.sqrt(distanceSq), 0.45)
                                 );
                              }
                              if (candidateMode != null && candidateTunnelCarve != null) {
                                 candidateMode[extIndex] = (byte)(road.mode().ordinal() + 1);
                                 candidateTunnelCarve[extIndex] = tunnelNeedsCarve;
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

   private static double[] crossingStationsOnRoad(
      RoadFeature road, int roadWidth, List<OsmStreetLightFeature> crossings, double blocksPerDegree, double worldScale
   ) {
      if (crossings.isEmpty()
         || roadWidth < 3
         || road.mode() == RoadMode.TUNNEL
         || RoadSurfaceStyle.isPedestrianLike(road)
         || RoadSurfaceStyle.isUnpaved(road.roadSurface())) {
         return RoadCrossingLayout.NO_CROSSINGS;
      }

      double[] xs = new double[road.pointCount()];
      double[] zs = new double[road.pointCount()];
      for (int i = 0; i < xs.length; i++) {
         xs[i] = road.lonAt(i) * blocksPerDegree;
         zs[i] = EarthProjection.latToBlockZ(road.latAt(i), worldScale);
      }

      double[] crossingXs = new double[crossings.size()];
      double[] crossingZs = new double[crossings.size()];
      for (int i = 0; i < crossingXs.length; i++) {
         crossingXs[i] = crossings.get(i).longitude() * blocksPerDegree;
         crossingZs[i] = EarthProjection.latToBlockZ(crossings.get(i).latitude(), worldScale);
      }

      return RoadCrossingLayout.crossingStations(xs, zs, crossingXs, crossingZs);
   }

   private EarthChunkGenerator.RoadColumnSample sampleRoadColumnForOverlay(
      int worldX,
      int worldZ,
      int chunkMinX,
      int chunkMinZ,
      int[] terrainSurfaces,
      int[] waterSurfaces,
      boolean[] waterFlags,
      Long2ObjectOpenHashMap<EarthChunkGenerator.RoadColumnSample> edgeColumnCache
   ) {
      if (worldX >= chunkMinX && worldX < chunkMinX + CHUNK_SIDE && worldZ >= chunkMinZ && worldZ < chunkMinZ + CHUNK_SIDE) {
         int localX = worldX - chunkMinX;
         int localZ = worldZ - chunkMinZ;
         int index = chunkIndex(localX, localZ);
         return new EarthChunkGenerator.RoadColumnSample(
            terrainSurfaces[index], waterSurfaces[index], waterFlags[index] && waterSurfaces[index] > terrainSurfaces[index]
         );
      } else {
         long packed = packColumn(worldX, worldZ);
         EarthChunkGenerator.RoadColumnSample cached = (EarthChunkGenerator.RoadColumnSample)edgeColumnCache.get(packed);
         if (cached != null) {
            return cached;
         } else {
            WaterSurfaceResolver.WaterColumnData column = this.resolveAuxWaterColumn(worldX, worldZ);
            EarthChunkGenerator.RoadColumnSample sampled = new EarthChunkGenerator.RoadColumnSample(
               column.terrainSurface(), column.waterSurface(), column.hasWater() && column.waterSurface() > column.terrainSurface()
            );
            edgeColumnCache.put(packed, sampled);
            return sampled;
         }
      }
   }

   private static EarthChunkGenerator.RoadWidths resolveRoadWidths(double worldScale) {
      double factor = roadWidthFactorForScale(worldScale);

      return new EarthChunkGenerator.RoadWidths(
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

   private static int bridgeRiseAtStation(double station, double totalLength, int bridgeLevel) {
      int requestedRise = Math.max(0, bridgeLevel) * OSM_ROAD_BRIDGE_LEVEL_HEIGHT;
      requestedRise = Math.min(requestedRise, OSM_ROAD_BRIDGE_MAX_RISE);
      if (requestedRise > 0 && !(totalLength <= 1.0E-6)) {
         double maxRiseByLength = totalLength / (2.0 * OSM_ROAD_BRIDGE_RAMP_HORIZONTAL_PER_VERTICAL);
         int targetRise = Math.min(requestedRise, Math.max(0, (int)Math.floor(maxRiseByLength)));
         if (targetRise <= 0) {
            return 0;
         } else {
            double clampedStation = Mth.clamp(station, 0.0, totalLength);
            double rampLength = targetRise * OSM_ROAD_BRIDGE_RAMP_HORIZONTAL_PER_VERTICAL;
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
      EarthChunkGenerator.RoadColumnSample localColumn,
      int bridgeLevel,
      RoadClass roadClass,
      double worldScale
   ) {
      int baseline = bridgeDeckBaselineAtStation(station, totalLength, startSurface, endSurface);
      int rise = bridgeRiseAtStation(station, totalLength, bridgeLevel);
      int clearance = bridgeClearanceAtStation(station, totalLength, roadClass, worldScale);
      int localRoadSurface = localColumn.roadSurface();
      int connectedDeck = baseline + rise;
      boolean valleyBridge = localColumn.hasWater() || localRoadSurface + bridgeValleyDepthBlocks(worldScale) < baseline;
      return valleyBridge ? Math.max(connectedDeck, localRoadSurface + clearance) : Math.max(connectedDeck, localRoadSurface);
   }

   private static int normalRoadDeckYAtCell(int centerSurface, int localSurface, double distanceFromCenter, double halfWidth) {
      int delta = localSurface - centerSurface;
      if (Math.abs(delta) <= 1 || halfWidth <= 0.75) {
         return centerSurface;
      }

      double edgeBlendStart = Math.max(0.5, halfWidth - 1.25);
      if (distanceFromCenter <= edgeBlendStart) {
         return centerSurface;
      }

      double blendRange = Math.max(0.5, halfWidth - edgeBlendStart);
      double blend = Mth.clamp((distanceFromCenter - edgeBlendStart) / blendRange, 0.0, 1.0);
      return (int)Math.round(Mth.lerp(blend, centerSurface, localSurface));
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
         double rampLength = targetClearance * OSM_ROAD_BRIDGE_RAMP_HORIZONTAL_PER_VERTICAL;
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

   private static int tunnelDeckAtStation(double station, double totalLength, int startSurface, int endSurface) {
      return interpolateDeckAtStation(station, totalLength, startSurface, endSurface);
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

   private static boolean shouldReplaceBridgeCandidate(boolean existingPresent, int existingDeckY, int newDeckY) {
      return !existingPresent || newDeckY > existingDeckY;
   }

   private static boolean shouldReplaceRoadCandidate(
      boolean existingPresent, int existingDeckY, byte existingModeId, boolean existingTunnelCarve, int newDeckY, RoadMode newMode, boolean newTunnelCarve
   ) {
      if (!existingPresent) {
         return true;
      } else if (newDeckY != existingDeckY) {
         return newDeckY > existingDeckY;
      } else {
         int existingModePriority = modePriority(existingModeId);
         if (newMode.priority() != existingModePriority) {
            return newMode.priority() > existingModePriority;
         } else {
            return newTunnelCarve != existingTunnelCarve ? newTunnelCarve : false;
         }
      }
   }

   private static int modePriority(byte modeId) {
      if (modeId <= 0) {
         return -1;
      } else {
         int index = modeId - 1;
         return index >= 0 && index < RoadMode.values().length ? RoadMode.values()[index].priority() : -1;
      }
   }

   private static void mergeBridgeOverlay(
      int index,
      int classId,
      int deckY,
      byte style,
      boolean marking,
      boolean[] bridgeOverlayPresent,
      int[] bridgeOverlayDeckY,
      byte[] bridgeOverlayClass,
      byte[] bridgeOverlayStyle,
      boolean[] bridgeOverlayMarking
   ) {
      if (!bridgeOverlayPresent[index]) {
         bridgeOverlayPresent[index] = true;
         bridgeOverlayDeckY[index] = deckY;
         bridgeOverlayClass[index] = (byte)classId;
         bridgeOverlayStyle[index] = style;
         bridgeOverlayMarking[index] = marking;
      } else {
         int existingDeck = bridgeOverlayDeckY[index];
         int existingClass = bridgeOverlayClass[index];
         if (deckY > existingDeck || deckY == existingDeck && classId < existingClass) {
            bridgeOverlayDeckY[index] = deckY;
            bridgeOverlayClass[index] = (byte)classId;
            bridgeOverlayStyle[index] = style;
            bridgeOverlayMarking[index] = marking;
         }
      }
   }

   private void rasterizeBridgeSupports(
      List<RoadFeature> roads,
      int roadWidth,
      double blocksPerDegree,
      int extMinX,
      int extMinZ,
      int extSide,
      int chunkMinX,
      int chunkMinZ,
      int[] terrainSurfaces,
      int[] waterSurfaces,
      boolean[] waterFlags,
      int chunkMinY,
      int chunkMaxY,
      Long2ObjectOpenHashMap<EarthChunkGenerator.RoadColumnSample> edgeColumnCache,
      byte[] resolvedClass,
      int[] resolvedDeckY,
      boolean[] bridgeOverlayPresent,
      int[] bridgeOverlayDeckY,
      EarthChunkGenerator.PreparedChunkBuildings preparedBuildings,
      boolean[] shaftPresent,
      int[] shaftBottomY,
      int[] shaftTopY,
      boolean[] capPresent,
      int[] capBottomY,
      int[] capTopY
   ) {
      if (roads.isEmpty() || roadWidth <= 0) {
         return;
      }

      double worldScale = EarthProjection.worldScaleFromBlocksPerDegree(blocksPerDegree);
      for (RoadFeature road : roads) {
         int featureRoadWidth = RoadSurfaceStyle.effectiveRoadWidth(road, roadWidth, worldScale);
         EarthChunkGenerator.RoadColumnSample startColumn = this.sampleRoadColumnForOverlay(
            Mth.floor(road.lonAt(0) * blocksPerDegree),
            Mth.floor(EarthProjection.latToBlockZ(road.latAt(0), worldScale)),
            chunkMinX,
            chunkMinZ,
            terrainSurfaces,
            waterSurfaces,
            waterFlags,
            edgeColumnCache
         );
         EarthChunkGenerator.RoadColumnSample endColumn = this.sampleRoadColumnForOverlay(
            Mth.floor(road.lonAt(road.pointCount() - 1) * blocksPerDegree),
            Mth.floor(EarthProjection.latToBlockZ(road.latAt(road.pointCount() - 1), worldScale)),
            chunkMinX,
            chunkMinZ,
            terrainSurfaces,
            waterSurfaces,
            waterFlags,
            edgeColumnCache
         );
         int startSurface = startColumn.roadSurface();
         int endSurface = endColumn.roadSurface();
         BridgeSupportLayout.SupportStyle style = BridgeSupportLayout.styleFor(road.roadClass(), featureRoadWidth);
         BridgeSupportLayout.forEachSupport(road, blocksPerDegree, worldScale, featureRoadWidth, placement -> {
            IntArrayList capCells = new IntArrayList();
            IntArrayList[] shaftCells = new IntArrayList[style.shaftCount()];
            int[] minTerrain = new int[style.shaftCount()];
            int[] maxTerrain = new int[style.shaftCount()];

            for (int i = 0; i < style.shaftCount(); i++) {
               shaftCells[i] = new IntArrayList();
               minTerrain[i] = Integer.MAX_VALUE;
               maxTerrain[i] = Integer.MIN_VALUE;
            }

            EarthChunkGenerator.RoadColumnSample supportCenterColumn = this.sampleRoadColumnForOverlay(
               Mth.floor(placement.centerX()),
               Mth.floor(placement.centerZ()),
               chunkMinX,
               chunkMinZ,
               terrainSurfaces,
               waterSurfaces,
               waterFlags,
               edgeColumnCache
            );
            int deckY = bridgeDeckYAtStation(
               placement.station(),
               placement.totalLength(),
               startSurface,
               endSurface,
               supportCenterColumn,
               road.bridgeLevel(),
               road.roadClass(),
               worldScale
            );
            int capTop = Math.min(chunkMaxY, deckY - 1);
            int capBottom = Math.max(chunkMinY, capTop - style.capThickness() + 1);
            if (capTop < capBottom) {
               return;
            }

            double radius = style.maxFootprintRadius() + 1.0;
            int minLocalX = Mth.clamp((int)Math.floor(placement.centerX() - radius) - extMinX, 0, extSide - 1);
            int maxLocalX = Mth.clamp((int)Math.ceil(placement.centerX() + radius) - extMinX, 0, extSide - 1);
            int minLocalZ = Mth.clamp((int)Math.floor(placement.centerZ() - radius) - extMinZ, 0, extSide - 1);
            int maxLocalZ = Mth.clamp((int)Math.ceil(placement.centerZ() + radius) - extMinZ, 0, extSide - 1);

            for (int localZ = minLocalZ; localZ <= maxLocalZ; localZ++) {
               int worldZ = extMinZ + localZ;

               for (int localX = minLocalX; localX <= maxLocalX; localX++) {
                  int worldX = extMinX + localX;
                  double deltaX = worldX - placement.centerX();
                  double deltaZ = worldZ - placement.centerZ();
                  double along = deltaX * placement.tangentX() + deltaZ * placement.tangentZ();
                  double across = deltaX * placement.normalX() + deltaZ * placement.normalZ();
                  int index = extIndex(localX, localZ, extSide);
                  if (Math.abs(along) <= style.capHalfAlong() && Math.abs(across) <= style.capHalfAcross()) {
                     capCells.add(index);
                  }

                  for (int shaftIndex = 0; shaftIndex < style.shaftCount(); shaftIndex++) {
                     double shaftAcross = style.shaftCount() == 1 ? 0.0 : (shaftIndex == 0 ? -style.shaftOffset() : style.shaftOffset());
                     if (Math.abs(along) <= style.shaftHalfAlong() && Math.abs(across - shaftAcross) <= style.shaftHalfAcross()) {
                        EarthChunkGenerator.RoadColumnSample column = this.sampleRoadColumnForOverlay(
                           worldX, worldZ, chunkMinX, chunkMinZ, terrainSurfaces, waterSurfaces, waterFlags, edgeColumnCache
                        );
                        int terrainSurface = column.terrainSurface();
                        if (!BridgeSupportRoadMask.overlapsRoad(
                           index, terrainSurface, capBottom - 1, resolvedClass, resolvedDeckY, bridgeOverlayPresent, bridgeOverlayDeckY
                        )) {
                           shaftCells[shaftIndex].add(index);
                           minTerrain[shaftIndex] = Math.min(minTerrain[shaftIndex], terrainSurface);
                           maxTerrain[shaftIndex] = Math.max(maxTerrain[shaftIndex], terrainSurface);
                        }
                     }
                  }
               }
            }

            // Nearby roads only remove directly overlapping support columns instead of suppressing the whole support.
            BridgeSupportRoadMask.retainRoadFreeSupportCells(
               capCells, index -> capBottom, capTop, resolvedClass, resolvedDeckY, bridgeOverlayPresent, bridgeOverlayDeckY
            );
            if (capCells.isEmpty()) {
               return;
            }

            int[] supportTops = new int[style.shaftCount()];
            boolean[] activeShafts = new boolean[style.shaftCount()];
            int activeShaftCount = 0;
            int requiredClearance = Math.max(1, Math.min(style.minClearance(), bridgeTargetClearanceBlocks(road.roadClass(), worldScale)));
            for (int shaftIndex = 0; shaftIndex < style.shaftCount(); shaftIndex++) {
               if (shaftCells[shaftIndex].isEmpty() || minTerrain[shaftIndex] == Integer.MAX_VALUE || maxTerrain[shaftIndex] == Integer.MIN_VALUE) {
                  continue;
               }

               if (capBottom - maxTerrain[shaftIndex] < requiredClearance) {
                  continue;
               }

               supportTops[shaftIndex] = capBottom - 1;
               if (supportTops[shaftIndex] < minTerrain[shaftIndex]) {
                  continue;
               }

               activeShafts[shaftIndex] = true;
               activeShaftCount++;
            }

            if (activeShaftCount == 0) {
               return;
            }

            for (int i = 0; i < capCells.size(); i++) {
               int index = capCells.getInt(i);
               if (this.bridgeSupportConflictsBuilding(
                  index,
                  capBottom,
                  capTop,
                  extMinX,
                  extMinZ,
                  extSide,
                  chunkMinX,
                  chunkMinZ,
                  preparedBuildings
               )) {
                  return;
               }
            }

            for (int shaftIndex = 0; shaftIndex < style.shaftCount(); shaftIndex++) {
               if (!activeShafts[shaftIndex]) {
                  continue;
               }

               for (int i = 0; i < shaftCells[shaftIndex].size(); i++) {
                  int index = shaftCells[shaftIndex].getInt(i);
                  int localBottom = this.bridgeSupportTerrainBottom(
                     index, extMinX, extMinZ, extSide, chunkMinX, chunkMinZ, terrainSurfaces, waterSurfaces, waterFlags, edgeColumnCache
                  );
                  if (supportTops[shaftIndex] < localBottom) {
                     continue;
                  }

                  if (this.bridgeSupportConflictsBuilding(
                     index,
                     localBottom,
                     supportTops[shaftIndex],
                     extMinX,
                     extMinZ,
                     extSide,
                     chunkMinX,
                     chunkMinZ,
                     preparedBuildings
                  )) {
                     return;
                  }
               }
            }

            for (int i = 0; i < capCells.size(); i++) {
               mergeBridgeSupportColumn(capCells.getInt(i), capBottom, capTop, capPresent, capBottomY, capTopY);
            }

            for (int shaftIndex = 0; shaftIndex < style.shaftCount(); shaftIndex++) {
               if (!activeShafts[shaftIndex]) {
                  continue;
               }

               for (int i = 0; i < shaftCells[shaftIndex].size(); i++) {
                  int index = shaftCells[shaftIndex].getInt(i);
                  int localBottom = this.bridgeSupportTerrainBottom(
                     index, extMinX, extMinZ, extSide, chunkMinX, chunkMinZ, terrainSurfaces, waterSurfaces, waterFlags, edgeColumnCache
                  );
                  if (supportTops[shaftIndex] < localBottom) {
                     continue;
                  }

                  mergeBridgeSupportColumn(
                     index,
                     localBottom,
                     supportTops[shaftIndex],
                     shaftPresent,
                     shaftBottomY,
                     shaftTopY
                  );
               }
            }
         });
      }
   }

   private boolean bridgeSupportConflictsBuilding(
      int extIndex,
      int bottomY,
      int topY,
      int extMinX,
      int extMinZ,
      int extSide,
      int chunkMinX,
      int chunkMinZ,
      EarthChunkGenerator.PreparedChunkBuildings preparedBuildings
   ) {
      if (topY < bottomY) {
         return false;
      }

      if (preparedBuildings != null) {
         int localX = extIndex % extSide;
         int localZ = extIndex / extSide;
         int worldX = extMinX + localX;
         int worldZ = extMinZ + localZ;
         int chunkLocalX = worldX - chunkMinX;
         int chunkLocalZ = worldZ - chunkMinZ;
         if (chunkLocalX >= 0
            && chunkLocalX < CHUNK_SIDE
            && chunkLocalZ >= 0
            && chunkLocalZ < CHUNK_SIDE
            && preparedBuildings.intersectsSpan(chunkLocalX, chunkLocalZ, bottomY, topY)) {
            return true;
         }
      }

      return false;
   }

   private RoadColumnSample bridgeSupportColumnSample(
      int extIndex,
      int extMinX,
      int extMinZ,
      int extSide,
      int chunkMinX,
      int chunkMinZ,
      int[] terrainSurfaces,
      int[] waterSurfaces,
      boolean[] waterFlags,
      Long2ObjectOpenHashMap<RoadColumnSample> edgeColumnCache
   ) {
      int localX = extIndex % extSide;
      int localZ = extIndex / extSide;
      int worldX = extMinX + localX;
      int worldZ = extMinZ + localZ;
      return this.sampleRoadColumnForOverlay(worldX, worldZ, chunkMinX, chunkMinZ, terrainSurfaces, waterSurfaces, waterFlags, edgeColumnCache);
   }

   private int bridgeSupportTerrainBottom(
      int extIndex,
      int extMinX,
      int extMinZ,
      int extSide,
      int chunkMinX,
      int chunkMinZ,
      int[] terrainSurfaces,
      int[] waterSurfaces,
      boolean[] waterFlags,
      Long2ObjectOpenHashMap<RoadColumnSample> edgeColumnCache
   ) {
      return this.bridgeSupportColumnSample(
            extIndex, extMinX, extMinZ, extSide, chunkMinX, chunkMinZ, terrainSurfaces, waterSurfaces, waterFlags, edgeColumnCache
         )
         .terrainSurface();
   }

   private static void mergeBridgeSupportColumn(int index, int bottomY, int topY, boolean[] present, int[] bottoms, int[] tops) {
      if (topY < bottomY) {
         return;
      }

      if (!present[index]) {
         present[index] = true;
         bottoms[index] = bottomY;
         tops[index] = topY;
      } else {
         bottoms[index] = Math.min(bottoms[index], bottomY);
         tops[index] = Math.max(tops[index], topY);
      }
   }

   private static int extIndex(int localX, int localZ, int side) {
      return localZ * side + localX;
   }

   private static long packColumn(int worldX, int worldZ) {
      return (long)worldX << 32 ^ worldZ & 4294967295L;
   }


   private static BlockState roadStateForClass(RoadClass roadClass) {
      return switch (roadClass) {
         case MAIN -> ROAD_MAIN_STATE;
         case NORMAL -> ROAD_NORMAL_STATE;
         case DIRT -> ROAD_DIRT_STATE;
      };
   }

   private static BlockState roadStateForStyle(int classId, byte style, boolean marking) {
      if (marking) {
         return ROAD_MARKING_STATE;
      }

      return switch (style) {
         case RoadSurfaceStyle.STYLE_PAVED_LIGHT -> ROAD_PAVED_LIGHT_STATE;
         case RoadSurfaceStyle.STYLE_PAVED_SMOOTH -> ROAD_PAVED_SMOOTH_STATE;
         case RoadSurfaceStyle.STYLE_PEDESTRIAN -> ROAD_PEDESTRIAN_STATE;
         case RoadSurfaceStyle.STYLE_GRAVEL -> ROAD_GRAVEL_STATE;
         case RoadSurfaceStyle.STYLE_DIRT -> ROAD_DIRT_STATE;
         case RoadSurfaceStyle.STYLE_COBBLESTONE -> ROAD_COBBLESTONE_STATE;
         case RoadSurfaceStyle.STYLE_STONE_PAVERS -> ROAD_STONE_PAVERS_STATE;
         case RoadSurfaceStyle.STYLE_BRICK -> ROAD_BRICK_STATE;
         case RoadSurfaceStyle.STYLE_SAND -> ROAD_SAND_STATE;
         case RoadSurfaceStyle.STYLE_WOOD -> ROAD_WOOD_STATE;
         case RoadSurfaceStyle.STYLE_CONCRETE -> ROAD_CONCRETE_STATE;
         default -> roadStateForClass(roadClassFromId(classId));
      };
   }


   private static RoadClass roadClassFromId(int classId) {
      return switch (classId) {
         case 1 -> RoadClass.MAIN;
         case 2 -> RoadClass.NORMAL;
         default -> RoadClass.DIRT;
      };
   }

   private static int roadClassId(RoadClass roadClass) {
      return switch (roadClass) {
         case MAIN -> 1;
         case NORMAL -> 2;
         case DIRT -> 3;
      };
   }

   private static boolean intersectsRoadLightBridgeSupport(
      int localX,
      int localZ,
      int minY,
      int maxY,
      boolean[] bridgeSupportShaftPresent,
      int[] bridgeSupportShaftBottomY,
      int[] bridgeSupportShaftTopY,
      boolean[] bridgeSupportCapPresent,
      int[] bridgeSupportCapBottomY,
      int[] bridgeSupportCapTopY
   ) {
      int index = chunkIndex(localX, localZ);
      return bridgeSupportShaftPresent[index] && spansOverlap(minY, maxY, bridgeSupportShaftBottomY[index], bridgeSupportShaftTopY[index])
         || bridgeSupportCapPresent[index] && spansOverlap(minY, maxY, bridgeSupportCapBottomY[index], bridgeSupportCapTopY[index]);
   }

   private static boolean spansOverlap(int minY, int maxY, int otherMinY, int otherMaxY) {
      return maxY >= otherMinY && minY <= otherMaxY;
   }

   private static int quantizeRoadCoordinate(double value) {
      return Mth.floor(value + 0.5);
   }

   private void clearRoadSurfaceColumn(
      WorldGenLevel level,
      ChunkAccess chunk,
      MutableBlockPos cursor,
      int worldX,
      int worldZ,
      int deckY,
      int terrainSurfaceY,
      int chunkMinY,
      int chunkMaxY
   ) {
      if (deckY < chunkMinY || deckY >= chunkMaxY) {
         return;
      }

      int topY = Math.min(chunkMaxY, Math.max(terrainSurfaceY, deckY + OSM_ROAD_SURFACE_CLEARANCE));
      for (int y = deckY + 1; y <= topY; y++) {
         cursor.set(worldX, y, worldZ);
         BlockState state = blockStateAt(level, chunk, cursor);
         if (isRoadSurfaceClearable(state)) {
            this.setChunkBlock(level, chunk, cursor, AIR_STATE);
         }
      }
   }

   private static boolean isRoadSurfaceClearable(BlockState state) {
      return !state.isAir()
         && !state.is(Blocks.BEDROCK)
         && !isRoadDeckState(state)
         && !state.is(BRIDGE_SUPPORT_SHAFT_STATE.getBlock())
         && !state.is(BRIDGE_SUPPORT_CAP_STATE.getBlock());
   }

   private static boolean isRoadDeckState(BlockState state) {
      return state.is(TellusBlockReferences.concreteBlock("GRAY"))
         || state.is(TellusBlockReferences.concreteBlock("LIGHT_GRAY"))
         || state.is(Blocks.SMOOTH_STONE)
         || state.is(Blocks.GRAVEL)
         || state.is(Blocks.DIRT_PATH)
         || state.is(TellusBlockReferences.concreteBlock("WHITE"));
   }

   private static boolean isRoadLightReplaceable(BlockState state) {
      return state.isAir()
         || state.is(Blocks.SNOW)
         || state.is(Blocks.POWDER_SNOW)
         || state.getFluidState().isEmpty() && state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
   }

   private static boolean isTunnelCarveReplaceable(BlockState state) {
      return isReplaceableCaveBlock(state) && !isRoadDeckState(state) && !state.is(BRIDGE_SUPPORT_SHAFT_STATE.getBlock()) && !state.is(BRIDGE_SUPPORT_CAP_STATE.getBlock());
   }

   private static boolean[] computeFloodGuardColumns(boolean[] waterFlags) {
      boolean[] result = new boolean[CHUNK_AREA];

      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         for (int localX = 0; localX < CHUNK_SIDE; localX++) {
            boolean nearWater = false;

            for (int dz = -2; dz <= 2 && !nearWater; dz++) {
               int z = localZ + dz;
               if (z >= 0 && z < CHUNK_SIDE) {
                  for (int dx = -2; dx <= 2; dx++) {
                     int x = localX + dx;
                     if (x >= 0 && x < CHUNK_SIDE && waterFlags[chunkIndex(x, z)]) {
                        nearWater = true;
                        break;
                     }
                  }
               }
            }

            result[chunkIndex(localX, localZ)] = nearWater;
         }
      }

      return result;
   }

   private int[] computeCarverGenerationFloorYByColumn(ChunkAccess chunk, WaterSurfaceResolver.WaterChunkData waterData) {
      int[] result = new int[CHUNK_AREA];
      int minY = MinecraftVersionCompat.minY(chunk);

      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         for (int localX = 0; localX < CHUNK_SIDE; localX++) {
            int index = chunkIndex(localX, localZ);
            int terrainSurface = waterData.terrainSurface(localX, localZ);
            result[index] = Math.max(
               minY, UndergroundGenerationDepthPolicy.generationFloorY(terrainSurface, this.settings.undergroundDepth())
            );
         }
      }

      return result;
   }

   private static boolean isReplaceableCaveBlock(BlockState state) {
      return isSolidCaveAnchor(state) && !state.hasBlockEntity() && !state.is(Blocks.BEDROCK);
   }

   private static boolean isSolidCaveAnchor(BlockState state) {
      return !state.isAir() && state.getFluidState().isEmpty() && !state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
   }

   private static int chunkIndex(int localX, int localZ) {
      return localZ * CHUNK_SIDE + localX;
   }

   private void applyUndergroundStructureProtection(
      StructureManager structures, ChunkAccess chunk, int[] terrainSurfaces, boolean preserveStructureCores
   ) {
      if (!this.settings.usesTerrainShell()) {
         return;
      }

      record ProtectionVolume(BoundingBox box, boolean cavernEnvelope) {
      }

      ChunkPos chunkPos = chunk.getPos();
      // The current chunk's references already include every structure intersecting it. Looking up
      // adjacent chunks can dereference a distant structure start outside WorldGenRegion's cache.
      Set<StructureStart> starts = new HashSet<>(WorldgenApi.structureStarts(structures, chunkPos, structure -> true));
      starts.removeIf(start -> start == null || !start.isValid());
      if (starts.isEmpty()) {
         return;
      }

      List<ProtectionVolume> protectionVolumes = new ArrayList<>();
      List<BoundingBox> structureCores = new ArrayList<>();
      for (StructureStart start : starts) {
         boolean cavernEnvelope = UndergroundStructureProtection.usesBoundedCavernEnvelope(
            start.getStructure().terrainAdaptation()
         );
         BoundingBox startBounds = start.getBoundingBox();
         if (cavernEnvelope) {
            for (StructurePiece piece : start.getPieces()) {
               BoundingBox pieceBounds = piece.getBoundingBox();
               protectionVolumes.add(new ProtectionVolume(pieceBounds, true));
               structureCores.add(pieceBounds);
            }
         } else {
            protectionVolumes.add(new ProtectionVolume(startBounds, false));
            structureCores.add(startBounds);
         }
      }
      int chunkMinX = chunkPos.getMinBlockX();
      int chunkMinZ = chunkPos.getMinBlockZ();
      int chunkMaxX = chunkMinX + CHUNK_MASK;
      int chunkMaxZ = chunkMinZ + CHUNK_MASK;
      int chunkMinY = MinecraftVersionCompat.minY(chunk);
      int chunkMaxY = chunkMinY + chunk.getHeight() - 1;
      MutableBlockPos cursor = new MutableBlockPos();

      for (ProtectionVolume volume : protectionVolumes) {
         BoundingBox box = volume.box();
         int horizontalThickness =
            UndergroundStructureProtection.horizontalProtectionThickness(volume.cavernEnvelope());
         int expandedMinX = box.minX() - horizontalThickness;
         int expandedMaxX = box.maxX() + horizontalThickness;
         int expandedMinZ = box.minZ() - horizontalThickness;
         int expandedMaxZ = box.maxZ() + horizontalThickness;
         int minX = Math.max(chunkMinX, expandedMinX);
         int maxX = Math.min(chunkMaxX, expandedMaxX);
         int minZ = Math.max(chunkMinZ, expandedMinZ);
         int maxZ = Math.min(chunkMaxZ, expandedMaxZ);
         if (minX > maxX || minZ > maxZ) {
            continue;
         }

         int protectionBottomY = Math.max(
            chunkMinY, UndergroundStructureProtection.protectionBottomY(box.minY())
         );
         for (int worldZ = minZ; worldZ <= maxZ; worldZ++) {
            int localZ = worldZ - chunkMinZ;
            for (int worldX = minX; worldX <= maxX; worldX++) {
               int localX = worldX - chunkMinX;
               int terrainSurface = terrainSurfaces != null && terrainSurfaces.length == CHUNK_AREA
                  ? terrainSurfaces[chunkIndex(localX, localZ)]
                  : this.resolveAuxWaterColumn(worldX, worldZ).terrainSurface();
               if (!UndergroundStructureProtection.needsTerrainExtension(
                  box.minY(), terrainSurface, this.settings.undergroundDepth()
               )) {
                  continue;
               }

               int terrainShellBottomY = Mth.clamp(
                  UndergroundStructureProtection.terrainShellBottomY(terrainSurface, this.settings.undergroundDepth()),
                  chunkMinY,
                  chunkMaxY
               );
               int fillTopY = Math.min(
                  UndergroundStructureProtection.protectionFillTopY(
                     terrainShellBottomY, box.minY(), volume.cavernEnvelope(), preserveStructureCores
                  ),
                  chunkMaxY
               );
               if (protectionBottomY > fillTopY) {
                  continue;
               }

               for (int y = protectionBottomY; y <= fillTopY; y++) {
                  if (preserveStructureCores && isInsideAnyStructureCore(worldX, y, worldZ, structureCores)) {
                     continue;
                  }

                  boolean bedrockSkin = UndergroundStructureProtection.isOuterBedrockSkin(
                     worldX,
                     y,
                     worldZ,
                     expandedMinX,
                     protectionBottomY,
                     expandedMinZ,
                     expandedMaxX,
                     expandedMaxZ,
                     !volume.cavernEnvelope()
                  );
                  BlockState state = bedrockSkin
                     ? BEDROCK_STATE
                     : volume.cavernEnvelope() ? DEEPSLATE_STATE : STONE_STATE;
                  cursor.set(worldX, y, worldZ);
                  if (!chunk.getBlockState(cursor).equals(state)) {
                     setChunkBlockStateDiscardingBlockEntity(chunk, cursor, state);
                  }
               }
            }
         }
      }
   }

   private static boolean isInsideAnyStructureCore(int x, int y, int z, List<BoundingBox> structureCores) {
      for (BoundingBox box : structureCores) {
         if (x >= box.minX()
            && x <= box.maxX()
            && y >= box.minY()
            && y <= box.maxY()
            && z >= box.minZ()
            && z <= box.maxZ()) {
            return true;
         }
      }
      return false;
   }

   @SuppressWarnings("unchecked")
   private static Holder<Biome>[] newBiomeCache(int size) {
      return (Holder<Biome>[])new Holder[size];
   }

   private List<UndergroundStructureExclusion.Box> collectUndergroundStructureExclusions(
      StructureManager structures, ChunkPos chunkPos
   ) {
      List<StructureStart> starts = WorldgenApi.structureStarts(structures,
         chunkPos,
         structure -> UndergroundStructureExclusion.protectsProjectedCarving(structure.terrainAdaptation())
      );
      if (starts.isEmpty()) {
         return List.of();
      }

      int chunkMinX = chunkPos.getMinBlockX();
      int chunkMinZ = chunkPos.getMinBlockZ();
      int chunkMaxX = chunkMinX + CHUNK_MASK;
      int chunkMaxZ = chunkMinZ + CHUNK_MASK;
      List<UndergroundStructureExclusion.Box> exclusions = new ArrayList<>();
      for (StructureStart start : starts) {
         if (start == null || !start.isValid()) {
            continue;
         }

         TerrainAdjustment adjustment = start.getStructure().terrainAdaptation();
         BoundingBox startBounds = start.getBoundingBox();
         int centerX = startBounds.minX() + (startBounds.maxX() - startBounds.minX()) / 2;
         int centerZ = startBounds.minZ() + (startBounds.maxZ() - startBounds.minZ()) / 2;
         int terrainSurface = this.resolveAuxWaterColumn(centerX, centerZ).terrainSurface();
         if (!UndergroundStructureExclusion.isUnderground(startBounds.maxY(), terrainSurface)) {
            continue;
         }

         boolean cavernEnvelope = UndergroundStructureProtection.usesBoundedCavernEnvelope(adjustment);
         boolean blocksFeaturePlacement = UndergroundStructureExclusion.protectsFeaturePlacement(adjustment);
         int carvingMargin = cavernEnvelope
            ? UndergroundStructureProtection.CAVERN_PIECE_CARVER_MARGIN
            : UndergroundStructureExclusion.CARVER_MARGIN;
         int intersectionMargin = blocksFeaturePlacement
            ? Math.max(carvingMargin, UndergroundStructureExclusion.FEATURE_PLACEMENT_MARGIN)
            : carvingMargin;

         for (StructurePiece piece : start.getPieces()) {
            BoundingBox bounds = piece.getBoundingBox();
            UndergroundStructureExclusion.Box exclusion = new UndergroundStructureExclusion.Box(
               bounds.minX(),
               bounds.minY(),
               bounds.minZ(),
               bounds.maxX(),
               bounds.maxY(),
               bounds.maxZ(),
               carvingMargin,
               blocksFeaturePlacement
            );
            if (!exclusion.intersectsHorizontal(
               chunkMinX,
               chunkMinZ,
               chunkMaxX,
               chunkMaxZ,
               intersectionMargin
            )) {
               continue;
            }
            exclusions.add(exclusion);
         }
      }
      return exclusions.isEmpty() ? List.of() : List.copyOf(exclusions);
   }

   private void carveStructureClearanceVolumes(StructureManager structures, ChunkAccess chunk) {
      if (this.settings.suppressesUndergroundGenerationForTerrainShell()) {
         return;
      }

      List<StructureStart> starts = WorldgenApi.structureStarts(structures,
         chunk.getPos(), structure -> shouldApplyStructureTerrainAdjustment(structure.terrainAdaptation())
      );
      if (!starts.isEmpty()) {
         ChunkPos pos = chunk.getPos();
         int chunkMinX = pos.getMinBlockX();
         int chunkMinZ = pos.getMinBlockZ();
         int chunkMaxX = chunkMinX + 15;
         int chunkMaxZ = chunkMinZ + 15;
         int chunkMinY = MinecraftVersionCompat.minY(chunk);
         int chunkMaxY = chunkMinY + chunk.getHeight() - 1;
         MutableBlockPos cursor = new MutableBlockPos();

         for (StructureStart start : starts) {
            if (start != null && start.isValid()) {
               TerrainAdjustment adjustment = start.getStructure().terrainAdaptation();
               int horizontalClearance = UndergroundStructureProtection.horizontalClearanceRadius(adjustment);
               int clearanceBelow = UndergroundStructureProtection.clearanceBelow(adjustment);
               int clearanceAbove = UndergroundStructureProtection.clearanceAbove(adjustment);
               for (StructurePiece piece : start.getPieces()) {
                 BoundingBox box = piece.getBoundingBox();
                 if (box.maxX() + horizontalClearance + 1 >= chunkMinX
                    && box.minX() - horizontalClearance - 1 <= chunkMaxX
                    && box.maxZ() + horizontalClearance + 1 >= chunkMinZ
                    && box.minZ() - horizontalClearance - 1 <= chunkMaxZ) {
                     int centerX = box.minX() + box.maxX() >> 1;
                     int centerZ = box.minZ() + box.maxZ() >> 1;
                     int terrainSurface = this.resolveAuxWaterColumn(centerX, centerZ).terrainSurface();
                     if (box.maxY() <= terrainSurface - 20) {
                        int coreMinX = box.minX() - 1;
                        int coreMaxX = box.maxX() + 1;
                        int coreMinZ = box.minZ() - 1;
                        int coreMaxZ = box.maxZ() + 1;
                        int coreMinY =
                           box.minY() + UndergroundStructureProtection.clearanceFloorOffset(adjustment);
                        int coreMaxY = box.maxY() + 0;
                        int minX = Math.max(chunkMinX, coreMinX - horizontalClearance);
                        int maxX = Math.min(chunkMaxX, coreMaxX + horizontalClearance);
                        int minZ = Math.max(chunkMinZ, coreMinZ - horizontalClearance);
                        int maxZ = Math.min(chunkMaxZ, coreMaxZ + horizontalClearance);
                        int minY = Math.max(chunkMinY + 1, coreMinY - clearanceBelow);
                        int maxY = Math.min(
                           chunkMaxY - 1,
                           UndergroundStructureProtection.clearanceCeilingY(adjustment, coreMaxY, terrainSurface)
                        );
                        if (maxY >= minY && maxX >= minX && maxZ >= minZ) {
                           for (int z = minZ; z <= maxZ; z++) {
                              for (int x = minX; x <= maxX; x++) {
                                 for (int y = minY; y <= maxY; y++) {
                                    if (isInsideStructureCore(x, y, z, coreMinX, coreMaxX, coreMinY, coreMaxY, coreMinZ, coreMaxZ)) {
                                       continue;
                                    }

                                    double nx = axisDistanceNormalized(x, coreMinX, coreMaxX, horizontalClearance);
                                    double nz = axisDistanceNormalized(z, coreMinZ, coreMaxZ, horizontalClearance);
                                    double ny = axisDistanceNormalized(y, coreMinY, coreMaxY, clearanceBelow, clearanceAbove);
                                    double distance = Math.sqrt(nx * nx + ny * ny + nz * nz);
                                    double threshold = 1.0 + this.structureClearanceNoiseJitter(x, y, z) * 0.22;
                                    if (!(distance > threshold)) {
                                       cursor.set(x, y, z);
                                       BlockState state = chunk.getBlockState(cursor);
                                       if (isReplaceableCaveBlock(state)) {
                                          setChunkBlockStateDiscardingBlockEntity(chunk, cursor, CAVE_AIR_STATE);
                                       }
                                    }
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private static boolean isInsideStructureCore(
      int x, int y, int z, int coreMinX, int coreMaxX, int coreMinY, int coreMaxY, int coreMinZ, int coreMaxZ
   ) {
      return x >= coreMinX && x <= coreMaxX && y >= coreMinY && y <= coreMaxY && z >= coreMinZ && z <= coreMaxZ;
   }

   private double structureClearanceNoiseJitter(int x, int y, int z) {
      long seed = seedFromCoords(x, y, z) ^ this.worldSeed ^ 7951840804584193857L;
      double t = Math.floorMod(seed, 2048L) / 2047.0;
      return t * 2.0 - 1.0;
   }

   private static double axisDistanceNormalized(int value, int coreMin, int coreMax, int shellRadius) {
      if (value < coreMin) {
         return (double)(coreMin - value) / Math.max(1, shellRadius);
      } else {
         return value > coreMax ? (double)(value - coreMax) / Math.max(1, shellRadius) : 0.0;
      }
   }

   private static double axisDistanceNormalized(int value, int coreMin, int coreMax, int shellRadiusBelow, int shellRadiusAbove) {
      if (value < coreMin) {
         return shellRadiusBelow <= 0 ? Double.POSITIVE_INFINITY : (double)(coreMin - value) / shellRadiusBelow;
      } else if (value > coreMax) {
         return shellRadiusAbove <= 0 ? Double.POSITIVE_INFINITY : (double)(value - coreMax) / shellRadiusAbove;
      } else {
         return 0.0;
      }
   }

   private static boolean shouldApplyStructureTerrainAdjustment(TerrainAdjustment adjustment) {
      return adjustment == TerrainAdjustment.BEARD_THIN || adjustment == TerrainAdjustment.BEARD_BOX;
   }

   private static int minSurfaceHeight(int[] terrainSurfaces) {
      int min = Integer.MAX_VALUE;

      for (int surface : terrainSurfaces) {
         if (surface < min) {
            min = surface;
         }
      }

      return min == Integer.MAX_VALUE ? 0 : min;
   }

   private EarthChunkGenerator.HeightGridBuildResult buildHeightGrid(
      ChunkPos pos, int step, int gridSize, boolean allowCacheReuse, boolean useLocalTerrainInputs
   ) {
      int[] heightGrid = new int[gridSize * gridSize];
      int cacheHits = 0;
      int cacheMisses = 0;
      boolean reusableLayout = allowCacheReuse && isReusableHeightGridLayout(step, gridSize);
      if (reusableLayout) {
         Arrays.fill(heightGrid, Integer.MIN_VALUE);
         cacheHits = this.heightGridCache.copyOverlaps(pos, step, gridSize, heightGrid, false);
      }

      int gridMinX = pos.getMinBlockX() - step;
      int gridMinZ = pos.getMinBlockZ() - step;
      int unavailableSamples = 0;
      for (int dz = 0; dz < gridSize; dz++) {
         int worldZ = gridMinZ + dz;
         int row = dz * gridSize;

         for (int dx = 0; dx < gridSize; dx++) {
            int index = row + dx;
            if (!reusableLayout || heightGrid[index] == Integer.MIN_VALUE) {
               int worldX = gridMinX + dx;
               int unavailableReads = TellusElevationSource.unavailableTileReads();
               heightGrid[index] = useLocalTerrainInputs ? this.sampleSurfaceHeightLocalOnly(worldX, worldZ) : this.sampleSurfaceHeight(worldX, worldZ);
               if (TellusElevationSource.unavailableTileReads() != unavailableReads) {
                  heightGrid[index] = Integer.MIN_VALUE;
                  unavailableSamples++;
               }
               cacheMisses++;
            }
         }
      }

      if (unavailableSamples > 0) {
         // Interpolate samples whose tile failed to download instead of leaving them at sea level until the refinement.
         this.fillMissingTerrainShellHeights(heightGrid, gridSize);
      }

      if (reusableLayout) {
         this.heightGridCache.put(pos, step, gridSize, heightGrid, unavailableSamples > 0);
      }

      return new EarthChunkGenerator.HeightGridBuildResult(heightGrid, cacheHits, cacheMisses);
   }

   private EarthChunkGenerator.TerrainShellHeightGridResult buildTerrainShellHeightGrid(ChunkPos pos, int step, int gridSize, boolean allowCacheReuse) {
      int[] heightGrid = new int[gridSize * gridSize];
      Arrays.fill(heightGrid, Integer.MIN_VALUE);
      int cacheHits = 0;
      boolean reusableLayout = allowCacheReuse && isReusableHeightGridLayout(step, gridSize);
      if (reusableLayout) {
         cacheHits = this.heightGridCache.copyOverlaps(pos, step, gridSize, heightGrid, true);
      }

      int initialMisses = 0;
      int gridMinX = pos.getMinBlockX() - step;
      int gridMinZ = pos.getMinBlockZ() - step;
      for (int dz = 0; dz < gridSize; dz++) {
         int worldZ = gridMinZ + dz;
         int row = dz * gridSize;

         for (int dx = 0; dx < gridSize; dx++) {
            int index = row + dx;
            if (heightGrid[index] == Integer.MIN_VALUE) {
               int worldX = gridMinX + dx;
               int sampled = this.sampleSurfaceHeightMemoryOnly(worldX, worldZ);
               if (sampled != Integer.MIN_VALUE) {
                  heightGrid[index] = sampled;
               } else {
                  initialMisses++;
               }
            }
         }
      }

      boolean usedFallback = initialMisses > 0;
      if (usedFallback) {
         this.fillMissingTerrainShellHeights(heightGrid, gridSize);
      }

      if (reusableLayout) {
         this.heightGridCache.put(pos, step, gridSize, heightGrid, usedFallback);
      }

      return new EarthChunkGenerator.TerrainShellHeightGridResult(heightGrid, cacheHits, initialMisses, usedFallback);
   }

   private void fillMissingTerrainShellHeights(int[] heightGrid, int gridSize) {
      int[] anchors = buildShellAnchorCoordinates(gridSize);
      int[][] coarse = new int[anchors.length][anchors.length];
      for (int z = 0; z < coarse.length; z++) {
         Arrays.fill(coarse[z], Integer.MIN_VALUE);
      }

      for (int anchorZIndex = 0; anchorZIndex < anchors.length; anchorZIndex++) {
         for (int anchorXIndex = 0; anchorXIndex < anchors.length; anchorXIndex++) {
            coarse[anchorZIndex][anchorXIndex] = nearestKnownTerrainHeight(heightGrid, gridSize, anchors[anchorXIndex], anchors[anchorZIndex], 2);
         }
      }

      int defaultHeight = this.seaLevel;
      int knownAnchorCount = 0;
      long knownAnchorSum = 0L;
      for (int[] coarseRow : coarse) {
         for (int coarseHeight : coarseRow) {
            if (coarseHeight != Integer.MIN_VALUE) {
               knownAnchorSum += coarseHeight;
               knownAnchorCount++;
            }
         }
      }

      if (knownAnchorCount > 0) {
         defaultHeight = Mth.floor((double)knownAnchorSum / knownAnchorCount);
      }

      defaultHeight = Mth.clamp(defaultHeight, this.minY, this.minY + this.height - 1);
      for (int anchorZIndex = 0; anchorZIndex < anchors.length; anchorZIndex++) {
         for (int anchorXIndex = 0; anchorXIndex < anchors.length; anchorXIndex++) {
            if (coarse[anchorZIndex][anchorXIndex] == Integer.MIN_VALUE) {
               int replacement = nearestKnownAnchorHeight(coarse, anchorXIndex, anchorZIndex);
               coarse[anchorZIndex][anchorXIndex] = replacement != Integer.MIN_VALUE ? replacement : defaultHeight;
            }
         }
      }

      for (int z = 0; z < gridSize; z++) {
         for (int x = 0; x < gridSize; x++) {
            int index = z * gridSize + x;
            if (heightGrid[index] != Integer.MIN_VALUE) {
               continue;
            }

            int lowAnchorX = lowerAnchorIndex(anchors, x);
            int highAnchorX = upperAnchorIndex(anchors, x);
            int lowAnchorZ = lowerAnchorIndex(anchors, z);
            int highAnchorZ = upperAnchorIndex(anchors, z);
            int h00 = coarse[lowAnchorZ][lowAnchorX];
            int h10 = coarse[lowAnchorZ][highAnchorX];
            int h01 = coarse[highAnchorZ][lowAnchorX];
            int h11 = coarse[highAnchorZ][highAnchorX];
            heightGrid[index] = bilinearInterpolateHeight(
               anchors[lowAnchorX], anchors[highAnchorX], anchors[lowAnchorZ], anchors[highAnchorZ], h00, h10, h01, h11, x, z
            );
         }
      }

      for (int z = 0; z < gridSize; z++) {
         for (int x = 0; x < gridSize; x++) {
            int index = z * gridSize + x;
            if (heightGrid[index] == Integer.MIN_VALUE) {
               int replacement = nearestKnownTerrainHeight(heightGrid, gridSize, x, z, gridSize);
               heightGrid[index] = replacement != Integer.MIN_VALUE ? replacement : defaultHeight;
            }
         }
      }
   }

   private static int[] buildShellAnchorCoordinates(int gridSize) {
      IntArrayList coords = new IntArrayList();
      for (int index = 0; index < gridSize; index += 4) {
         coords.add(index);
      }

      if (coords.isEmpty() || coords.getInt(coords.size() - 1) != gridSize - 1) {
         coords.add(gridSize - 1);
      }

      return coords.toIntArray();
   }

   private static int nearestKnownAnchorHeight(int[][] anchors, int centerX, int centerZ) {
      int maxRadius = Math.max(anchors.length, anchors[0].length);
      for (int radius = 1; radius <= maxRadius; radius++) {
         long sum = 0L;
         int count = 0;
         for (int z = Math.max(0, centerZ - radius); z <= Math.min(anchors.length - 1, centerZ + radius); z++) {
            for (int x = Math.max(0, centerX - radius); x <= Math.min(anchors[z].length - 1, centerX + radius); x++) {
               int value = anchors[z][x];
               if (value != Integer.MIN_VALUE) {
                  sum += value;
                  count++;
               }
            }
         }

         if (count > 0) {
            return Mth.floor((double)sum / count);
         }
      }

      return Integer.MIN_VALUE;
   }

   private static int nearestKnownTerrainHeight(int[] heightGrid, int gridSize, int centerX, int centerZ, int maxRadius) {
      if (centerX >= 0 && centerX < gridSize && centerZ >= 0 && centerZ < gridSize) {
         int center = heightGrid[centerZ * gridSize + centerX];
         if (center != Integer.MIN_VALUE) {
            return center;
         }
      }

      for (int radius = 1; radius <= maxRadius; radius++) {
         long sum = 0L;
         int count = 0;
         for (int z = Math.max(0, centerZ - radius); z <= Math.min(gridSize - 1, centerZ + radius); z++) {
            for (int x = Math.max(0, centerX - radius); x <= Math.min(gridSize - 1, centerX + radius); x++) {
               int value = heightGrid[z * gridSize + x];
               if (value != Integer.MIN_VALUE) {
                  sum += value;
                  count++;
               }
            }
         }

         if (count > 0) {
            return Mth.floor((double)sum / count);
         }
      }

      return Integer.MIN_VALUE;
   }

   private static int lowerAnchorIndex(int[] anchors, int value) {
      int best = 0;
      for (int index = 0; index < anchors.length; index++) {
         if (anchors[index] > value) {
            break;
         }

         best = index;
      }

      return best;
   }

   private static int upperAnchorIndex(int[] anchors, int value) {
      for (int index = 0; index < anchors.length; index++) {
         if (anchors[index] >= value) {
            return index;
         }
      }

      return anchors.length - 1;
   }

   private static int bilinearInterpolateHeight(
      int x0, int x1, int z0, int z1, int h00, int h10, int h01, int h11, int x, int z
   ) {
      if (x0 == x1 && z0 == z1) {
         return h00;
      } else if (x0 == x1) {
         double tz = (z - z0) / (double)Math.max(1, z1 - z0);
         return Mth.floor(Mth.lerp(tz, h00, h01));
      } else if (z0 == z1) {
         double tx = (x - x0) / (double)Math.max(1, x1 - x0);
         return Mth.floor(Mth.lerp(tx, h00, h10));
      } else {
         double tx = (x - x0) / (double)Math.max(1, x1 - x0);
         double tz = (z - z0) / (double)Math.max(1, z1 - z0);
         double low = Mth.lerp(tx, h00, h10);
         double high = Mth.lerp(tx, h01, h11);
         return Mth.floor(Mth.lerp(tz, low, high));
      }
   }

   private static boolean isReusableHeightGridLayout(int step, int gridSize) {
      return step == 4 && gridSize == 24;
   }

   private static String sampleKoppenCode(int blockX, int blockZ, double worldScale) {
      String koppen = KOPPEN_SOURCE.sampleDitheredCode(blockX, blockZ, worldScale);
      return koppen != null ? koppen : KOPPEN_SOURCE.findNearestCode(blockX, blockZ, worldScale);
   }

   private void applyShorelineBankRamp(
      ChunkPos pos, int[] terrainSurfaces, int[] waterSurfaces, boolean[] waterFlags, WaterSurfaceResolver.WaterChunkData waterData
   ) {
      if (!this.settings.shorelineBlendCliffLimit()) {
         return;
      }

      int blendRadius = Math.max(this.settings.riverLakeShorelineBlend(), this.settings.oceanShorelineBlend());
      if (blendRadius <= 0) {
         return;
      }

      int padding = blendRadius;
      int extSide = CHUNK_SIDE + 2 * padding;
      int extArea = extSide * extSide;
      boolean[] extWater = new boolean[extArea];
      int[] extWaterSurface = new int[extArea];
      int[] extDistance = new int[extArea];
      Arrays.fill(extDistance, Integer.MAX_VALUE);

      int chunkMinX = pos.getMinBlockX();
      int chunkMinZ = pos.getMinBlockZ();
      ArrayDeque<Integer> queue = new ArrayDeque<>();

      for (int z = 0; z < extSide; z++) {
         for (int x = 0; x < extSide; x++) {
            int extIndex = z * extSide + x;
            boolean inChunk = x >= padding && x < padding + CHUNK_SIDE && z >= padding && z < padding + CHUNK_SIDE;
            if (inChunk) {
               int chunkIdx = chunkIndex(x - padding, z - padding);
               extWater[extIndex] = waterFlags[chunkIdx];
               extWaterSurface[extIndex] = waterSurfaces[chunkIdx];
            } else {
               int worldX = chunkMinX - padding + x;
               int worldZ = chunkMinZ - padding + z;
               int coverClass = this.sampleCoverClass(worldX, worldZ);
               WaterSurfaceResolver.WaterColumnData wd = this.waterResolver.resolveFastColumnData(worldX, worldZ, coverClass);
               extWater[extIndex] = wd.hasWater();
               extWaterSurface[extIndex] = wd.waterSurface();
            }
            if (extWater[extIndex]) {
               extDistance[extIndex] = 0;
               queue.add(extIndex);
            }
         }
      }

      while (!queue.isEmpty()) {
         int idx = queue.removeFirst();
         int d = extDistance[idx];
         if (d >= blendRadius) {
            continue;
         }
         int waterSurfaceY = extWaterSurface[idx];
         int x = idx % extSide;
         int z = idx / extSide;
         int nextDistance = d + 1;
         for (int dz = -1; dz <= 1; dz++) {
            int nz = z + dz;
            if (nz < 0 || nz >= extSide) {
               continue;
            }
            for (int dx = -1; dx <= 1; dx++) {
               if (dx == 0 && dz == 0) {
                  continue;
               }
               int nx = x + dx;
               if (nx < 0 || nx >= extSide) {
                  continue;
               }
               int neighborIdx = nz * extSide + nx;
               if (extWater[neighborIdx]) {
                  continue;
               }
               if (nextDistance < extDistance[neighborIdx]) {
                  extDistance[neighborIdx] = nextDistance;
                  extWaterSurface[neighborIdx] = waterSurfaceY;
                  queue.add(neighborIdx);
               }
            }
         }
      }

      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         for (int localX = 0; localX < CHUNK_SIDE; localX++) {
            int chunkIdx = chunkIndex(localX, localZ);
            if (waterFlags[chunkIdx] || waterData.isInlandBank(localX, localZ)) {
               continue;
            }
            int extIndex = (localZ + padding) * extSide + (localX + padding);
            int distance = extDistance[extIndex];
            if (distance == Integer.MAX_VALUE || distance == 0) {
               continue;
            }
            int waterSurfaceY = extWaterSurface[extIndex];
            int currentSurface = terrainSurfaces[chunkIdx];
            if (currentSurface - waterSurfaceY < SHORELINE_BANK_RAMP_MIN_CLIFF) {
               continue;
            }
            int maxAllowed = waterSurfaceY + SHORELINE_BANK_RAMP_MAX_SLOPE * distance;
            if (currentSurface > maxAllowed) {
               terrainSurfaces[chunkIdx] = maxAllowed;
            }
         }
      }
   }

   private void repairAnomalousChunkTerrain(
      int[] terrainSurfaces,
      int[] waterSurfaces,
      boolean[] waterFlags,
      boolean[] oceanFlags,
      int[] coverClasses,
      int[] heightGrid,
      int gridSize,
      int step,
      int minY,
      int maxY,
      WaterSurfaceResolver.WaterChunkData waterData
   ) {
      int[] repaired = (int[])terrainSurfaces.clone();

      for (int pass = 0; pass < 4; pass++) {
         int[] source = (int[])repaired.clone();
         boolean changed = false;

         for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
               int index = chunkIndex(localX, localZ);
               int surface = source[index];
               if (!waterData.isInlandBank(localX, localZ)
                  && !(waterData.hasWater(localX, localZ) && !waterData.isOcean(localX, localZ))
                  && this.shouldRepairTerrainAnomaly(coverClasses[index], surface, waterFlags[index])) {
                  int gridIndex = (localZ + step) * gridSize + localX + step;
                  int east = sampleNeighborHeight(source, localX + 1, localZ, heightGrid, gridIndex + 1);
                  int west = sampleNeighborHeight(source, localX - 1, localZ, heightGrid, gridIndex - 1);
                  int north = sampleNeighborHeight(source, localX, localZ - 1, heightGrid, gridIndex - gridSize);
                  int south = sampleNeighborHeight(source, localX, localZ + 1, heightGrid, gridIndex + gridSize);
                  int northEast = sampleNeighborHeight(source, localX + 1, localZ - 1, heightGrid, gridIndex - gridSize + 1);
                  int northWest = sampleNeighborHeight(source, localX - 1, localZ - 1, heightGrid, gridIndex - gridSize - 1);
                  int southEast = sampleNeighborHeight(source, localX + 1, localZ + 1, heightGrid, gridIndex + gridSize + 1);
                  int southWest = sampleNeighborHeight(source, localX - 1, localZ + 1, heightGrid, gridIndex + gridSize - 1);
                  int repairedHeight = repairAnomalousTerrainHeightFromNeighbors(surface, east, west, north, south, northEast, northWest, southEast, southWest);
                  if (repairedHeight != surface) {
                     repaired[index] = Mth.clamp(repairedHeight, minY, maxY);
                     changed = true;
                  }
               }
            }
         }

         for (int localZ = 0; localZ < 16; localZ++) {
            for (int localXx = 0; localXx < 16; localXx++) {
               int index = chunkIndex(localXx, localZ);
               int gridIndex = (localZ + step) * gridSize + localXx + step;
               heightGrid[gridIndex] = repaired[index];
            }
         }

         if (!changed) {
            break;
         }
      }

      System.arraycopy(repaired, 0, terrainSurfaces, 0, repaired.length);

      for (int localZ = 0; localZ < 16; localZ++) {
         for (int localXx = 0; localXx < 16; localXx++) {
            int index = chunkIndex(localXx, localZ);
	            if (!waterFlags[index]) {
	               waterSurfaces[index] = terrainSurfaces[index];
	            } else if (terrainSurfaces[index] >= waterSurfaces[index]) {
	               if (waterSurfaces[index] > minY) {
	                  terrainSurfaces[index] = waterSurfaces[index] - 1;
               } else {
                  waterFlags[index] = false;
                  waterSurfaces[index] = terrainSurfaces[index];
               }
            }

            int gridIndex = (localZ + step) * gridSize + localXx + step;
            heightGrid[gridIndex] = terrainSurfaces[index];
         }
      }
   }

   private static int sampleNeighborHeight(int[] chunkSurfaces, int localX, int localZ, int[] heightGrid, int fallbackGridIndex) {
      return localX >= 0 && localX < 16 && localZ >= 0 && localZ < 16 ? chunkSurfaces[chunkIndex(localX, localZ)] : heightGrid[fallbackGridIndex];
   }

   private int repairAnomalousSurfaceHeight(int worldX, int worldZ, int surface, int coverClass, int minY, int maxY) {
      return this.repairAnomalousSurfaceHeight(worldX, worldZ, surface, coverClass, minY, maxY, false);
   }

   private int repairAnomalousSurfaceHeight(int worldX, int worldZ, int surface, int coverClass, int minY, int maxY, boolean hasWater) {
      if (!this.shouldRepairTerrainAnomaly(coverClass, surface, hasWater)) {
         return Mth.clamp(surface, minY, maxY);
      } else {
         int east = this.sampleSurfaceHeight(worldX + 1, worldZ);
         int west = this.sampleSurfaceHeight(worldX - 1, worldZ);
         int north = this.sampleSurfaceHeight(worldX, worldZ - 1);
         int south = this.sampleSurfaceHeight(worldX, worldZ + 1);
         int northEast = this.sampleSurfaceHeight(worldX + 1, worldZ - 1);
         int northWest = this.sampleSurfaceHeight(worldX - 1, worldZ - 1);
         int southEast = this.sampleSurfaceHeight(worldX + 1, worldZ + 1);
         int southWest = this.sampleSurfaceHeight(worldX - 1, worldZ + 1);
         int repaired = repairAnomalousTerrainHeightFromNeighbors(surface, east, west, north, south, northEast, northWest, southEast, southWest);
         return Mth.clamp(repaired, minY, maxY);
      }
   }

   private boolean shouldRepairTerrainAnomaly(int coverClass, int surface, boolean hasWater) {
      int heightAboveSea = surface - this.seaLevel;
      return heightAboveSea < 50 ? false : !hasWater && !isWaterCoverClass(coverClass) || heightAboveSea >= 90;
   }

   private static boolean isWaterCoverClass(int coverClass) {
      return coverClass == 80 || coverClass == 95;
   }

   private static int repairAnomalousTerrainHeightFromNeighbors(
      int center, int east, int west, int north, int south, int northEast, int northWest, int southEast, int southWest
   ) {
      return TerrainAnomalyRepair.repairHeightFromNeighbors(center, east, west, north, south, northEast, northWest, southEast, southWest);
   }

   private static int resolveSolidSectionMaxIndex(ChunkAccess chunk, int chunkMinY, int minSurface, int sectionCount) {
      if (sectionCount == 0) {
         return -1;
      } else {
         int sectionIndex = chunk.getSectionIndex(minSurface);
         int sectionBottom = chunkMinY + (sectionIndex << 4);
         int sectionTop = sectionBottom + 15;
         int solidMaxIndex = minSurface >= sectionTop ? sectionIndex : sectionIndex - 1;
         return solidMaxIndex < 0 ? -1 : Math.min(solidMaxIndex, sectionCount - 1);
      }
   }

   private static void fillSolidSections(
      LevelChunkSection[] sections,
      boolean[] solidSections,
      int[] sectionTopYs,
      int solidMaxIndex,
      BlockState stone,
      BlockState deepslate,
      int deepslateStart,
      EarthChunkGenerator.SolidSectionFillProfiler profiler
   ) {
      int sectionCount = sections.length;
      long sectionScanStartNs = beginFullChunkProfiling();

      for (int i = 0; i <= solidMaxIndex && i < sectionCount; i++) {
         int topY = sectionTopYs[i];
         int bottomY = topY - 15;
         if (bottomY >= 0 || topY < 0) {
            BlockState fill = topY < deepslateStart ? deepslate : stone;
            long sectionAccessStartNs = beginFullChunkProfiling();
            LevelChunkSection section = Objects.requireNonNull(sections[i], "section");
            profiler.sectionAccessNs += elapsedFullChunkProfilingSince(sectionAccessStartNs);
            fillSection(section, fill, profiler);
            solidSections[i] = true;
         }
      }

      long totalSectionScanNs = elapsedFullChunkProfilingSince(sectionScanStartNs);
      long attributedNs = profiler.sectionAccessNs + profiler.sectionWriteNs + profiler.recalcNs;
      profiler.scanNs += Math.max(0L, totalSectionScanNs - attributedNs);
   }

   private static void fillSolidSections(
      EarthChunkGenerator.ChunkSectionWriter writer,
      boolean[] solidSections,
      int[] sectionTopYs,
      int solidMaxIndex,
      BlockState stone,
      BlockState deepslate,
      int deepslateStart,
      EarthChunkGenerator.SolidSectionFillProfiler profiler
   ) {
      int sectionCount = sectionTopYs.length;
      long sectionScanStartNs = beginFullChunkProfiling();

      for (int i = 0; i <= solidMaxIndex && i < sectionCount; i++) {
         int topY = sectionTopYs[i];
         int bottomY = topY - 15;
         if (bottomY >= 0 || topY < 0) {
            BlockState fill = topY < deepslateStart ? deepslate : stone;
            writer.fillSectionConstant(i, fill, profiler);
            solidSections[i] = true;
         }
      }

      long totalSectionScanNs = elapsedFullChunkProfilingSince(sectionScanStartNs);
      long attributedNs = profiler.sectionAccessNs + profiler.sectionWriteNs + profiler.recalcNs;
      profiler.scanNs += Math.max(0L, totalSectionScanNs - attributedNs);
   }

   private static void fillSection(LevelChunkSection section, BlockState fill, EarthChunkGenerator.SolidSectionFillProfiler profiler) {
      PalettedContainer<BlockState> states = section.getStates();
      long sectionWriteStartNs = beginFullChunkProfiling();
      section.acquire();

      try {
         for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
               for (int x = 0; x < 16; x++) {
                  states.getAndSetUnchecked(x, y, z, fill);
               }
            }
         }
      } finally {
         section.release();
      }
      profiler.sectionWriteNs += elapsedFullChunkProfilingSince(sectionWriteStartNs);

      long sectionRecalcStartNs = beginFullChunkProfiling();
      section.recalcBlockCounts();
      profiler.recalcNs += elapsedFullChunkProfilingSince(sectionRecalcStartNs);
   }

   private static void fillStoneColumnSpan(
      LevelChunkSection[] sections,
      boolean[] touchedSections,
      int chunkMinY,
      int localX,
      int localZ,
      int startY,
      int endY,
      int deepslateStart,
      BlockState stone,
      BlockState deepslate
   ) {
      if (endY < startY) {
         return;
      }

      int sectionIndex = (startY - chunkMinY) >> 4;
      if (sectionIndex < 0 || sectionIndex >= sections.length) {
         return;
      }

      LevelChunkSection section = Objects.requireNonNull(sections[sectionIndex], "section");
      PalettedContainer<BlockState> states = section.getStates();
      int sectionBottomY = chunkMinY + (sectionIndex << 4);
      section.acquire();

      try {
         for (int worldY = startY; worldY <= endY; worldY++) {
            states.getAndSetUnchecked(localX, worldY - sectionBottomY, localZ, worldY < deepslateStart ? deepslate : stone);
         }
      } finally {
         section.release();
      }

      touchedSections[sectionIndex] = true;
   }

   private static void fillColumnConstant(
      LevelChunkSection[] sections, boolean[] touchedSections, int chunkMinY, int localX, int localZ, int startY, int endY, BlockState state
   ) {
      if (endY < startY) {
         return;
      }

      int currentY = startY;
      while (currentY <= endY) {
         int sectionIndex = (currentY - chunkMinY) >> 4;
         if (sectionIndex < 0 || sectionIndex >= sections.length) {
            break;
         }

         LevelChunkSection section = Objects.requireNonNull(sections[sectionIndex], "section");
         PalettedContainer<BlockState> states = section.getStates();
         int sectionBottomY = chunkMinY + (sectionIndex << 4);
         int localStartY = Math.max(0, currentY - sectionBottomY);
         int localEndY = Math.min(15, endY - sectionBottomY);
         section.acquire();

         try {
            for (int localY = localStartY; localY <= localEndY; localY++) {
               states.getAndSetUnchecked(localX, localY, localZ, state);
            }
         } finally {
            section.release();
         }

         touchedSections[sectionIndex] = true;
         currentY = sectionBottomY + 16;
      }
   }

   private static void recalcFilledSections(LevelChunkSection[] sections, boolean[] solidSections, boolean[] touchedSections) {
      for (int i = 0; i < sections.length && i < touchedSections.length; i++) {
         if (touchedSections[i] && !solidSections[i]) {
            Objects.requireNonNull(sections[i], "section").recalcBlockCounts();
         }
      }
   }

   private void filterVillageStarts(RegistryAccess registryAccess, ChunkAccess chunk) {
      Map<Structure, StructureStart> starts = chunk.getAllStarts();
      if (!starts.isEmpty()) {
         Registry<Structure> registry = MinecraftVersionCompat.registry(registryAccess, Registries.STRUCTURE);

         for (Entry<Structure, StructureStart> entry : starts.entrySet()) {
            StructureStart start = entry.getValue();
            if (start != null && start.isValid()) {
               Structure structure = Objects.requireNonNull(entry.getKey(), "structure");
               if (this.isVillageStructure(registry, structure) && (this.isVillageStartTooSteep(start) || this.isVillageStartInWater(start))) {
                  chunk.setStartForStructure(structure, StructureStart.INVALID_START);
               }
            }
         }
      }
   }

   private boolean isVillageStartTooSteep(StructureStart start) {
      return this.isStructureStartTooSteep(start, 4, 4, 6);
   }

   private boolean isVillageStartInWater(StructureStart start) {
      return this.structureFootprintTouchesWater(start.getBoundingBox(), VILLAGE_WATER_SAMPLE_MARGIN, VILLAGE_WATER_SAMPLE_STEP);
   }

   private void filterWoodlandMansionStarts(RegistryAccess registryAccess, ChunkAccess chunk) {
      Map<Structure, StructureStart> starts = chunk.getAllStarts();
      if (!starts.isEmpty()) {
         Registry<Structure> registry = MinecraftVersionCompat.registry(registryAccess, Registries.STRUCTURE);

         for (Entry<Structure, StructureStart> entry : starts.entrySet()) {
            StructureStart start = entry.getValue();
            if (start != null && start.isValid()) {
               Structure structure = Objects.requireNonNull(entry.getKey(), "structure");
               if (this.isWoodlandMansionStructure(registry, structure) && this.isWoodlandMansionStartTooSteep(start)) {
                  chunk.setStartForStructure(structure, StructureStart.INVALID_START);
               }
            }
         }
      }
   }

   private boolean isWoodlandMansionStartTooSteep(StructureStart start) {
      return this.isStructureStartTooSteep(start, 8, 6, 8);
   }

   private void filterAbandonedCampStarts(RegistryAccess registryAccess, ChunkAccess chunk) {
      Map<Structure, StructureStart> starts = chunk.getAllStarts();
      if (!starts.isEmpty()) {
         Registry<Structure> registry = MinecraftVersionCompat.registry(registryAccess, Registries.STRUCTURE);

         for (Entry<Structure, StructureStart> entry : starts.entrySet()) {
            StructureStart start = entry.getValue();
            if (start != null && start.isValid()) {
               Structure structure = Objects.requireNonNull(entry.getKey(), "structure");
               if (this.isAbandonedCampStructure(registry, structure) && !this.isAbandonedCampSiteUsable(start, chunk)) {
                  chunk.setStartForStructure(structure, StructureStart.INVALID_START);
               }
            }
         }
      }
   }

   // Tellus terrain ignores terrain_adaptation, so the rigid camp only sits right on dry ground within a block of its floor.
   private boolean isAbandonedCampSiteUsable(StructureStart start, LevelHeightAccessor heights) {
      BoundingBox bounds = start.getBoundingBox();
      if (!this.landmarks.intersecting(bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ()).isEmpty()) {
         return false;
      }

      // Every piece joins the tent at its floor; campsite templates may reach below it for buried chests.
      int floorY = start.getPieces().get(0).getBoundingBox().minY();
      int minY = MinecraftVersionCompat.minY(heights);
      int maxY = MinecraftVersionCompat.maxY(heights);
      for (StructurePiece piece : start.getPieces()) {
         BoundingBox box = piece.getBoundingBox();
         for (int z = box.minZ(); z <= box.maxZ(); z++) {
            for (int x = box.minX(); x <= box.maxX(); x++) {
               EarthChunkGenerator.ColumnHeights column = this.resolveFastColumnHeights(x, z, minY, maxY, this.sampleCoverClass(x, z));
               if (column.hasWater() || !VanillaStructurePlacement.supportsRigidFloor(column.terrainSurface(), floorY)) {
                  return false;
               }
            }
         }
      }

      return true;
   }

   private List<BoundingBox> abandonedCampPieces(RegistryAccess registryAccess, StructureManager structures, ChunkPos pos) {
      if (!this.settings.addAbandonedCamps()) {
         return List.of();
      }

      Registry<Structure> registry = MinecraftVersionCompat.registry(registryAccess, Registries.STRUCTURE);
      List<BoundingBox> pieces = new ArrayList<>();
      for (StructureStart start : WorldgenApi.structureStarts(structures, pos, structure -> this.isAbandonedCampStructure(registry, structure))) {
         if (start.isValid()) {
            for (StructurePiece piece : start.getPieces()) {
               pieces.add(piece.getBoundingBox());
            }
         }
      }

      return pieces;
   }

   private static boolean abandonedCampSuppressesTree(List<BoundingBox> campPieces, int worldX, int worldZ) {
      for (BoundingBox box : campPieces) {
         if (worldX >= box.minX() - ABANDONED_CAMP_TREE_CLEARANCE
            && worldX <= box.maxX() + ABANDONED_CAMP_TREE_CLEARANCE
            && worldZ >= box.minZ() - ABANDONED_CAMP_TREE_CLEARANCE
            && worldZ <= box.maxZ() + ABANDONED_CAMP_TREE_CLEARANCE) {
            return true;
         }
      }

      return false;
   }

   private void filterStartsCollidingWithOsm(RegistryAccess registryAccess, ChunkAccess chunk) {
      double worldScale = this.settings.worldScale();
      boolean roadsActive = this.settings.enableRoads() && OSM_ROAD_SOURCE.available() && worldScale > 0.0 && worldScale <= OSM_ROAD_MAX_SCALE;
      boolean buildingsActive = this.settings.enableBuildings() && OSM_BUILDING_SOURCE.available() && worldScale > 0.0 && worldScale <= OSM_BUILDING_MAX_SCALE;
      if (!roadsActive && !buildingsActive) return;
      Map<Structure, StructureStart> starts = chunk.getAllStarts();
      if (starts.isEmpty()) return;
      Registry<Structure> registry = MinecraftVersionCompat.registry(registryAccess, Registries.STRUCTURE);
      EarthChunkGenerator.RoadWidths roadWidths = roadsActive ? resolveRoadWidths(worldScale) : null;
      for (Entry<Structure, StructureStart> entry : starts.entrySet()) {
         StructureStart start = entry.getValue();
         if (start == null || !start.isValid()) continue;
         Structure structure = Objects.requireNonNull(entry.getKey(), "structure");
         if (this.shouldAvoidOsmCollision(registry, structure)
            && this.doesStructureStartCollideWithOsm(start, roadsActive, buildingsActive, roadWidths)) {
            chunk.setStartForStructure(structure, StructureStart.INVALID_START);
         }
      }
   }

   private boolean doesStructureStartCollideWithOsm(
      StructureStart start, boolean roadsActive, boolean buildingsActive, EarthChunkGenerator.RoadWidths roadWidths
   ) {
      BoundingBox box = start.getBoundingBox();
      if (buildingsActive && this.structureCollidesWithBuildings(box)) {
         return true;
      } else {
         return roadsActive && roadWidths != null ? this.structureCollidesWithRoads(box, roadWidths) : false;
      }
   }

   private boolean structureCollidesWithBuildings(BoundingBox box) {
      int marginBlocks = 2;
      double blocksPerDegree = blocksPerDegree(this.settings.worldScale());
      double minX = structureFootprintMinX(box);
      double maxX = structureFootprintMaxX(box);
      double minZ = structureFootprintMinZ(box);
      double maxZ = structureFootprintMaxZ(box);
      OsmQueryMode queryMode = this.shouldUseStructureOsmSyncFallback() ? OsmQueryMode.BLOCKING : OsmQueryMode.NON_BLOCKING;
      EarthChunkGenerator.OsmBuildingQueryResult query = this.fetchOsmBuildingsForAreaDetailed(
         box.minX(), box.minZ(), box.maxX(), box.maxZ(), marginBlocks, queryMode
      );
      if (queryMode == OsmQueryMode.NON_BLOCKING && query.hadCacheMisses() && query.features().isEmpty()) {
         EarthChunkGenerator.ChunkDetailPerf.recordSkippedBlockingFallback();
         return false;
      }

      for (OsmBuildingFeature building : query.features()) {
         if (this.structureIntersectsBuilding(box, building, blocksPerDegree, minX, minZ, maxX, maxZ)) {
            return true;
         }
      }

      return false;
   }

   private boolean structureCollidesWithRoads(BoundingBox box, EarthChunkGenerator.RoadWidths roadWidths) {
      int marginBlocks = Math.max(roadWidths.main(), Math.max(roadWidths.normal(), roadWidths.dirt())) + 2;
      OsmQueryMode queryMode = this.shouldUseStructureOsmSyncFallback() ? OsmQueryMode.BLOCKING : OsmQueryMode.NON_BLOCKING;
      EarthChunkGenerator.OsmRoadQueryResult query = this.fetchOsmRoadsForAreaDetailed(
         box.minX(), box.minZ(), box.maxX(), box.maxZ(), marginBlocks, queryMode
      );
      if (queryMode == OsmQueryMode.NON_BLOCKING && query.hadCacheMisses() && query.features().isEmpty()) {
         EarthChunkGenerator.ChunkDetailPerf.recordSkippedBlockingFallback();
         return false;
      }

      double blocksPerDegree = blocksPerDegree(this.settings.worldScale());
      double worldScale = this.settings.worldScale();

      for (RoadFeature road : query.features()) {
         if (road.mode() != RoadMode.TUNNEL && this.structureIntersectsRoad(box, road, roadWidths, blocksPerDegree, worldScale)) {
            return true;
         }
      }

      return false;
   }

   private boolean structureIntersectsBuilding(
      BoundingBox box, OsmBuildingFeature building, double blocksPerDegree, double minX, double minZ, double maxX, double maxZ
   ) {
      if (building.maxBlockX(blocksPerDegree) < minX
         || building.minBlockX(blocksPerDegree) > maxX
         || building.maxBlockZ(this.settings.worldScale()) < minZ
         || building.minBlockZ(this.settings.worldScale()) > maxZ) {
         return false;
      } else if (building.containsWorld(minX, minZ, this.settings.worldScale())
         || building.containsWorld(minX, maxZ, this.settings.worldScale())
         || building.containsWorld(maxX, minZ, this.settings.worldScale())
         || building.containsWorld(maxX, maxZ, this.settings.worldScale())) {
         return true;
      } else {
         for (int part = 0; part < building.partCount(); part++) {
            int points = building.pointCount(part);
            if (points >= 2) {
               double previousX = building.lonAt(part, points - 1) * blocksPerDegree;
               double previousZ = EarthProjection.latToBlockZ(building.latAt(part, points - 1), this.settings.worldScale());

               for (int i = 0; i < points; i++) {
                  double currentX = building.lonAt(part, i) * blocksPerDegree;
                  double currentZ = EarthProjection.latToBlockZ(building.latAt(part, i), this.settings.worldScale());
                  if (pointInRect(currentX, currentZ, minX, minZ, maxX, maxZ)
                     || segmentIntersectsRect(previousX, previousZ, currentX, currentZ, minX, minZ, maxX, maxZ)) {
                     return true;
                  }

                  previousX = currentX;
                  previousZ = currentZ;
               }
            }
         }

         return false;
      }
   }

   private boolean structureIntersectsRoad(
      BoundingBox box, RoadFeature road, EarthChunkGenerator.RoadWidths roadWidths, double blocksPerDegree, double worldScale
   ) {
      int roadWidth = switch (road.roadClass()) {
         case MAIN -> roadWidths.main();
         case NORMAL -> roadWidths.normal();
         case DIRT -> roadWidths.dirt();
      };
      double halfWidth = Math.max(0.5, (roadWidth - 1) * 0.5);
      double minX = structureFootprintMinX(box) - halfWidth;
      double maxX = structureFootprintMaxX(box) + halfWidth;
      double minZ = structureFootprintMinZ(box) - halfWidth;
      double maxZ = structureFootprintMaxZ(box) + halfWidth;
      int pointCount = road.pointCount();
      if (pointCount < 2) {
         return false;
      } else {
         double previousX = road.lonAt(0) * blocksPerDegree;
         double previousZ = EarthProjection.latToBlockZ(road.latAt(0), worldScale);
         if (pointInRect(previousX, previousZ, minX, minZ, maxX, maxZ)) {
            return true;
         } else {
            for (int i = 1; i < pointCount; i++) {
               double currentX = road.lonAt(i) * blocksPerDegree;
               double currentZ = EarthProjection.latToBlockZ(road.latAt(i), worldScale);
               if (segmentIntersectsRect(previousX, previousZ, currentX, currentZ, minX, minZ, maxX, maxZ)) {
                  return true;
               }

               previousX = currentX;
               previousZ = currentZ;
            }

            return false;
         }
      }
   }

   private boolean shouldAvoidOsmCollision(Registry<Structure> registry, Structure structure) {
      String path = MinecraftVersionCompat.registryKeyPath(registry, structure);
      if (path == null) {
         return false;
      } else {
         return path.startsWith("village")
            || VanillaStructurePlacement.isWoodlandMansionPath(path)
            || path.equals("desert_pyramid")
            || path.equals("desert_temple")
            || path.equals("jungle_pyramid")
            || path.equals("jungle_temple")
            || path.equals("pillager_outpost")
            || path.equals("igloo")
            || path.equals("swamp_hut")
            || path.equals("witch_hut")
            || path.startsWith("ruined_portal")
            || path.startsWith("trail_ruins")
            || VanillaStructurePlacement.isAbandonedCampPath(path);
      }
   }

   private static double structureFootprintMinX(BoundingBox box) {
      return box.minX() - 0.5;
   }

   private static double structureFootprintMaxX(BoundingBox box) {
      return box.maxX() + 0.5;
   }

   private static double structureFootprintMinZ(BoundingBox box) {
      return box.minZ() - 0.5;
   }

   private static double structureFootprintMaxZ(BoundingBox box) {
      return box.maxZ() + 0.5;
   }

   private static boolean pointInRect(double x, double z, double minX, double minZ, double maxX, double maxZ) {
      return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
   }

   private static boolean segmentIntersectsRect(double x1, double z1, double x2, double z2, double minX, double minZ, double maxX, double maxZ) {
      return pointInRect(x1, z1, minX, minZ, maxX, maxZ)
         || pointInRect(x2, z2, minX, minZ, maxX, maxZ)
         || segmentsIntersect(x1, z1, x2, z2, minX, minZ, maxX, minZ)
         || segmentsIntersect(x1, z1, x2, z2, maxX, minZ, maxX, maxZ)
         || segmentsIntersect(x1, z1, x2, z2, maxX, maxZ, minX, maxZ)
         || segmentsIntersect(x1, z1, x2, z2, minX, maxZ, minX, minZ);
   }

   private static boolean segmentsIntersect(double ax, double az, double bx, double bz, double cx, double cz, double dx, double dz) {
      double abx = bx - ax;
      double abz = bz - az;
      double acx = cx - ax;
      double acz = cz - az;
      double adx = dx - ax;
      double adz = dz - az;
      double cdx = dx - cx;
      double cdz = dz - cz;
      double cax = ax - cx;
      double caz = az - cz;
      double cbx = bx - cx;
      double cbz = bz - cz;
      double cross1 = cross(abx, abz, acx, acz);
      double cross2 = cross(abx, abz, adx, adz);
      double cross3 = cross(cdx, cdz, cax, caz);
      double cross4 = cross(cdx, cdz, cbx, cbz);
      double epsilon = 1.0E-7;
      if (Math.abs(cross1) <= epsilon && onSegment(ax, az, bx, bz, cx, cz)) {
         return true;
      } else if (Math.abs(cross2) <= epsilon && onSegment(ax, az, bx, bz, dx, dz)) {
         return true;
      } else if (Math.abs(cross3) <= epsilon && onSegment(cx, cz, dx, dz, ax, az)) {
         return true;
      } else if (Math.abs(cross4) <= epsilon && onSegment(cx, cz, dx, dz, bx, bz)) {
         return true;
      } else {
         return cross1 > 0.0 != cross2 > 0.0 && cross3 > 0.0 != cross4 > 0.0;
      }
   }

   private static double cross(double ax, double az, double bx, double bz) {
      return ax * bz - az * bx;
   }

   private static boolean onSegment(double ax, double az, double bx, double bz, double px, double pz) {
      return px >= Math.min(ax, bx) - 1.0E-7
         && px <= Math.max(ax, bx) + 1.0E-7
         && pz >= Math.min(az, bz) - 1.0E-7
         && pz <= Math.max(az, bz) + 1.0E-7;
   }

   private boolean isStructureStartTooSteep(StructureStart start, int margin, int sampleStep, int maxHeightDelta) {
      BoundingBox box = start.getBoundingBox();
      int minX = box.minX() - margin;
      int maxX = box.maxX() + margin;
      int minZ = box.minZ() - margin;
      int maxZ = box.maxZ() + margin;
      int minHeight = Integer.MAX_VALUE;
      int maxHeight = Integer.MIN_VALUE;
      int stride = Math.max(1, sampleStep);

      for (int z = minZ; z <= maxZ; z += stride) {
         for (int x = minX; x <= maxX; x += stride) {
            int surface = this.sampleSurfaceHeight(x, z);
            if (surface < minHeight) {
               minHeight = surface;
            }

            if (surface > maxHeight) {
               maxHeight = surface;
            }

            if (maxHeight - minHeight > maxHeightDelta) {
               return true;
            }
         }
      }

      return maxHeight - minHeight > maxHeightDelta;
   }

   private boolean structureFootprintTouchesWater(BoundingBox box, int margin, int sampleStep) {
      int minX = box.minX() - margin;
      int maxX = box.maxX() + margin;
      int minZ = box.minZ() - margin;
      int maxZ = box.maxZ() + margin;
      int stride = Math.max(1, sampleStep);

      for (int z = minZ; z <= maxZ; z += stride) {
         if (this.structureFootprintRowTouchesWater(minX, maxX, z, stride)) {
            return true;
         }
      }

      return (maxZ - minZ) % stride != 0 && this.structureFootprintRowTouchesWater(minX, maxX, maxZ, stride);
   }

   private boolean structureFootprintRowTouchesWater(int minX, int maxX, int z, int stride) {
      for (int x = minX; x <= maxX; x += stride) {
         if (this.isStructureWaterColumn(x, z)) {
            return true;
         }
      }

      return (maxX - minX) % stride != 0 && this.isStructureWaterColumn(maxX, z);
   }

   private boolean isStructureWaterColumn(int worldX, int worldZ) {
      int coverClass = this.sampleCoverClass(worldX, worldZ);
      WaterSurfaceResolver.WaterColumnData column = this.waterResolver.resolveColumnData(worldX, worldZ, coverClass);
      return column.hasWater();
   }

   private boolean isVillageStructure(Registry<Structure> registry, Structure structure) {
      String path = MinecraftVersionCompat.registryKeyPath(registry, structure);
      return path != null && path.startsWith("village");
   }

   private boolean isWoodlandMansionStructure(Registry<Structure> registry, Structure structure) {
      String path = MinecraftVersionCompat.registryKeyPath(registry, structure);
      return path != null && VanillaStructurePlacement.isWoodlandMansionPath(path);
   }

   private boolean isAbandonedCampStructure(Registry<Structure> registry, Structure structure) {
      String path = MinecraftVersionCompat.registryKeyPath(registry, structure);
      return path != null && VanillaStructurePlacement.isAbandonedCampPath(path);
   }

   public int getGenDepth() {
      return this.height;
   }

   public int getSeaLevel() {
      return this.seaLevel;
   }

   public int getMinY() {
      return this.minY;
   }

   public int getBaseHeight(int x, int z,  Types heightmapType,  LevelHeightAccessor heightAccessor,  RandomState random) {
      if (this.isFastSpawnMode()) {
         int maxY = MinecraftVersionCompat.maxY(heightAccessor) - 1;
         return Mth.clamp(this.seaLevel + 1, MinecraftVersionCompat.minY(heightAccessor), maxY);
      } else {
         int coverClass = this.sampleCoverClass(x, z);
         EarthChunkGenerator.ColumnHeights column = this.resolveFastColumnHeights(x, z, MinecraftVersionCompat.minY(heightAccessor), MinecraftVersionCompat.maxY(heightAccessor), coverClass);
         int surface = column.terrainSurface();
         if (heightmapType == Types.OCEAN_FLOOR_WG || heightmapType == Types.OCEAN_FLOOR) {
            return surface + 1;
         } else {
            return column.hasWater() ? Math.max(surface, column.waterSurface()) + 1 : surface + 1;
         }
      }
   }


   public NoiseColumn getBaseColumn(int x, int z,  LevelHeightAccessor heightAccessor,  RandomState random) {
      int minY = MinecraftVersionCompat.minY(heightAccessor);
      int height = heightAccessor.getHeight();
      BlockState[] states = new BlockState[height];
      Arrays.fill(states, AIR_STATE);
      int coverClass = this.sampleCoverClass(x, z);
      EarthChunkGenerator.ColumnHeights column = this.resolveFastColumnHeights(x, z, minY, minY + height, coverClass);
      int surface = column.terrainSurface();
      int surfaceIndex = surface - minY;

      if (this.settings.usesTerrainShell()) {
         int supportBottomY = Math.max(minY, surface - this.settings.undergroundDepth());
         int deepslateBoundaryY = TellusCaveDepthMapper.actualDeepslateBoundaryY(surface, supportBottomY + 1, this.seaLevel);
         for (int y = supportBottomY; y <= surface; y++) {
            int index = y - minY;
            if (index >= 0 && index < states.length) {
               states[index] = y == supportBottomY ? BEDROCK_STATE : y < deepslateBoundaryY ? DEEPSLATE_STATE : STONE_STATE;
            }
         }
      } else {
         for (int i = 0; i <= surfaceIndex; i++) {
            if (i >= 0 && i < states.length) {
               int y = minY + i;
               states[i] = y < 0 ? DEEPSLATE_STATE : STONE_STATE;
            }
         }

         int bedrockIndex = this.minY - minY;
         if (bedrockIndex >= 0 && bedrockIndex < states.length) {
            states[bedrockIndex] = BEDROCK_STATE;
         }
      }

      if (column.hasWater()) {
         int waterTop = column.waterSurface();
         int waterIndex = waterTop - minY;

         for (int ix = surfaceIndex + 1; ix <= waterIndex; ix++) {
            if (ix >= 0 && ix < states.length) {
               states[ix] = WATER_STATE;
            }
         }
      }

      return Objects.requireNonNull(new NoiseColumn(minY, states), "noiseColumn");
   }

   protected void addTellusDebugInfo( List<String> info,  RandomState random,  BlockPos pos) {
      info.add(String.format("Tellus scale: %.1f", this.settings.worldScale()));
   }

   private boolean isFastSpawnMode() {
      return this.fastSpawnMode.get();
   }

   private void disableFastSpawnMode() {
      if (this.fastSpawnMode.compareAndSet(true, false)) {
         if (this.biomeSource instanceof EarthBiomeSource earthBiomeSource) {
            earthBiomeSource.setFastSpawnMode(false);
         }
      }
   }

   private void placeTrees(WorldGenLevel level, ChunkAccess chunk, List<BoundingBox> campPieces) {
      ChunkPos pos = chunk.getPos();
      long chunkKey = MinecraftVersionCompat.packChunkPos(MinecraftVersionCompat.chunkX(pos), MinecraftVersionCompat.chunkZ(pos));
      EarthChunkGenerator.ChunkDecorationContext decorationContext = this.chunkDecorationContexts.get(chunkKey);
      EarthChunkGenerator.PreparedChunkBuildings preparedBuildings = this.preparedChunkBuildings.get(
         MinecraftVersionCompat.packChunkPos(MinecraftVersionCompat.chunkX(pos), MinecraftVersionCompat.chunkZ(pos))
      );
      int chunkMinX = pos.getMinBlockX();
      int chunkMinZ = pos.getMinBlockZ();
      int chunkMaxX = chunkMinX + CHUNK_MASK;
      int chunkMaxZ = chunkMinZ + CHUNK_MASK;
      int shorelineBlendRadius = Math.max(this.settings.riverLakeShorelineBlend(), this.settings.oceanShorelineBlend());
      int treeCellSize = this.treePlacementCellSize();
      int cellMinX = Math.floorDiv(chunkMinX, treeCellSize);
      int cellMaxX = Math.floorDiv(chunkMaxX, treeCellSize);
      int cellMinZ = Math.floorDiv(chunkMinZ, treeCellSize);
      int cellMaxZ = Math.floorDiv(chunkMaxZ, treeCellSize);
      long worldSeed = level.getSeed();

      for (int cellX = cellMinX; cellX <= cellMaxX; cellX++) {
         for (int cellZ = cellMinZ; cellZ <= cellMaxZ; cellZ++) {
            TellusProceduralTreeGenerator.TreeAnchor anchor = this.treeAnchorForCell(cellX, cellZ, worldSeed);
            long seed = anchor.seed();
            int worldX = anchor.worldX();
            int worldZ = anchor.worldZ();
            if (worldX >= chunkMinX && worldX <= chunkMaxX && worldZ >= chunkMinZ && worldZ <= chunkMaxZ) {
               int localX = worldX - chunkMinX;
               int localZ = worldZ - chunkMinZ;
               if (preparedBuildings != null && preparedBuildings.suppressesTrees(localX, localZ)
                  || this.landmarkSuppressesTree(worldX, worldZ)
                  || abandonedCampSuppressesTree(campPieces, worldX, worldZ)) {
                  continue;
               }

               int coverClass = decorationContext != null ? decorationContext.coverClass(localX, localZ) : this.sampleTreeCoverClass(worldX, worldZ);
               boolean nearWater = false;
               if (shorelineBlendRadius > 0) {
                  nearWater = decorationContext != null && decorationContext.canResolveNearWaterWithinChunk(localX, localZ, shorelineBlendRadius)
                     ? decorationContext.isNearWaterWithinChunk(localX, localZ, shorelineBlendRadius)
                     : this.isNearWater(worldX, worldZ, shorelineBlendRadius);
               }

               if ((coverClass == MountainSurfaceRules.ESA_TREE_COVER || this.settings.randomBiomes()) && !nearWater) {
                  int expectedSurface = decorationContext != null ? decorationContext.terrainSurface(localX, localZ) : this.sampleSurfaceHeight(worldX, worldZ);
                  if (expectedSurface >= this.seaLevel) {
                     int topY = level.getHeight(Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ) - 1;
                     if (topY >= MinecraftVersionCompat.minY(level)
                        && topY >= this.seaLevel
                        && expectedSurface - topY <= TREE_MAX_SURFACE_DROP
                        && topY - expectedSurface <= TREE_MAX_SURFACE_RISE) {
                        BlockPos ground = new BlockPos(worldX, topY, worldZ);
                        BlockState groundState = level.getBlockState(ground);
                        if (!isRoadDeckState(groundState)
                           && !isRoadDeckState(level.getBlockState(ground.below()))
                           && isSolidCaveAnchor(groundState)
                           && !groundState.is(BlockTags.LOGS)
                           && !groundState.is(BlockTags.LEAVES)) {
                           BlockPos position = ground.above();
                           Holder<Biome> biome = decorationContext != null ? decorationContext.biome(localX, localZ) : level.getBiome(position);
                           if (!biome.is(Biomes.MANGROVE_SWAMP)) {
                              List<TreeFeatureHandle> features = treeFeaturesForBiome(biome, this.settings.hugeRedMushrooms());
                              if (!features.isEmpty() && this.shouldPlaceTreesForCover(coverClass, biome, worldX, worldZ, seed)) {
                                 if (!groundState.is(BlockTags.DIRT)) {
                                    level.setBlock(ground, GRASS_BLOCK_STATE, 260);
                                 }

                                 if (this.settings.customTrees()) {
                                    TellusCanopyHeightSource.CanopySample canopy = CANOPY_HEIGHT_SOURCE.sampleCanopy(
                                       worldX, worldZ, this.settings.worldScale()
                                    );
                                    ResolveEcoregion ecoregion = RESOLVE_SOURCE.sampleEcoregion(
                                       worldX, worldZ, this.settings.worldScale()
                                    );
                                    TellusProceduralTreeGenerator.place(level, ground, biome, ecoregion, canopy, seed);
                                 } else {
                                    RandomSource random = RandomSource.create(seed);
                                    TreeFeatureHandle feature = features.get(random.nextInt(features.size()));
                                    feature.place(level, this, random, position);
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private List<EarthChunkGenerator.PreparedTreePlacement> prepareDeferredTreePlacements(
      EarthChunkGenerator.ChunkGenerationContext context, EarthChunkGenerator.PreparedChunkBuildings preparedBuildings
   ) {
      ChunkPos pos = context.pos();
      int chunkMinX = pos.getMinBlockX();
      int chunkMinZ = pos.getMinBlockZ();
      int chunkMaxX = chunkMinX + CHUNK_MASK;
      int chunkMaxZ = chunkMinZ + CHUNK_MASK;
      int shorelineBlendRadius = Math.max(this.settings.riverLakeShorelineBlend(), this.settings.oceanShorelineBlend());
      int treeCellSize = this.treePlacementCellSize();
      int cellMinX = Math.floorDiv(chunkMinX, treeCellSize);
      int cellMaxX = Math.floorDiv(chunkMaxX, treeCellSize);
      int cellMinZ = Math.floorDiv(chunkMinZ, treeCellSize);
      int cellMaxZ = Math.floorDiv(chunkMaxZ, treeCellSize);
      long worldSeed = this.worldSeed;
      List<EarthChunkGenerator.PreparedTreePlacement> placements = new ArrayList<>();

      for (int cellX = cellMinX; cellX <= cellMaxX; cellX++) {
         for (int cellZ = cellMinZ; cellZ <= cellMaxZ; cellZ++) {
            TellusProceduralTreeGenerator.TreeAnchor anchor = this.treeAnchorForCell(cellX, cellZ, worldSeed);
            long seed = anchor.seed();
            int worldX = anchor.worldX();
            int worldZ = anchor.worldZ();
            if (worldX < chunkMinX || worldX > chunkMaxX || worldZ < chunkMinZ || worldZ > chunkMaxZ) {
               continue;
            }

            int localX = worldX - chunkMinX;
            int localZ = worldZ - chunkMinZ;
            if (preparedBuildings != null && preparedBuildings.suppressesTrees(localX, localZ) || this.landmarkSuppressesTree(worldX, worldZ)) {
               continue;
            }

            int index = chunkIndex(localX, localZ);
            int coverClass = context.coverClasses()[index];
            if (coverClass != MountainSurfaceRules.ESA_TREE_COVER && !this.settings.randomBiomes()) {
               continue;
            }

            boolean nearWater = false;
            if (shorelineBlendRadius > 0) {
               nearWater = localX - shorelineBlendRadius >= 0
                  && localX + shorelineBlendRadius <= CHUNK_MASK
                  && localZ - shorelineBlendRadius >= 0
                  && localZ + shorelineBlendRadius <= CHUNK_MASK
                     ? hasWaterNear(context.waterFlags(), localX, localZ, shorelineBlendRadius)
                     : this.isNearWater(worldX, worldZ, shorelineBlendRadius);
            }

            if (nearWater) {
               continue;
            }

            int expectedSurface = context.terrainSurfaces()[index];
            if (expectedSurface < this.seaLevel) {
               continue;
            }

            Holder<Biome> biome = context.sampleBiome(worldX, worldZ, expectedSurface + 1);
            List<TreeFeatureHandle> features = treeFeaturesForBiome(biome, this.settings.hugeRedMushrooms());
            if (biome.is(Biomes.MANGROVE_SWAMP) || features.isEmpty() || !this.shouldPlaceTreesForCover(coverClass, biome, worldX, worldZ, seed)) {
               continue;
            }

            placements.add(new EarthChunkGenerator.PreparedTreePlacement(worldX, worldZ, expectedSurface, biome, seed));
         }
      }

      return placements.isEmpty() ? List.of() : List.copyOf(placements);
   }

   private void applyPreparedTreePlacements(
      WorldGenLevel level, ChunkAccess chunk, List<EarthChunkGenerator.PreparedTreePlacement> placements, List<BoundingBox> campPieces
   ) {
      for (EarthChunkGenerator.PreparedTreePlacement placement : placements) {
         if (!abandonedCampSuppressesTree(campPieces, placement.worldX(), placement.worldZ())) {
            this.applyPreparedTreePlacement(level, placement);
         }
      }
   }

   private void applyPreparedTreePlacement(WorldGenLevel level, EarthChunkGenerator.PreparedTreePlacement placement) {
      int worldX = placement.worldX();
      int worldZ = placement.worldZ();
      int topY = level.getHeight(Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ) - 1;
      if (topY < MinecraftVersionCompat.minY(level)
         || topY < this.seaLevel
         || placement.expectedSurface() - topY > TREE_MAX_SURFACE_DROP
         || topY - placement.expectedSurface() > TREE_MAX_SURFACE_RISE) {
         return;
      }

      BlockPos ground = new BlockPos(worldX, topY, worldZ);
      BlockState groundState = level.getBlockState(ground);
      if (isRoadDeckState(groundState)
         || isRoadDeckState(level.getBlockState(ground.below()))
         || !isSolidCaveAnchor(groundState)
         || groundState.is(BlockTags.LOGS)
         || groundState.is(BlockTags.LEAVES)) {
         return;
      }

      Holder<Biome> biome = placement.biome();
      if (biome.is(Biomes.MANGROVE_SWAMP)) {
         return;
      }

      List<TreeFeatureHandle> features = treeFeaturesForBiome(biome, this.settings.hugeRedMushrooms());
      if (features.isEmpty()) {
         return;
      }

      if (!groundState.is(BlockTags.DIRT)) {
         level.setBlock(ground, GRASS_BLOCK_STATE, 260);
      }

      if (this.settings.customTrees()) {
         TellusCanopyHeightSource.CanopySample canopy = CANOPY_HEIGHT_SOURCE.sampleCanopy(
            worldX, worldZ, this.settings.worldScale()
         );
         ResolveEcoregion ecoregion = RESOLVE_SOURCE.sampleEcoregion(
            worldX, worldZ, this.settings.worldScale()
         );
         TellusProceduralTreeGenerator.place(level, ground, biome, ecoregion, canopy, placement.seed());
      } else {
         RandomSource random = RandomSource.create(placement.seed());
         TreeFeatureHandle feature = features.get(random.nextInt(features.size()));
         feature.place(level, this, random, ground.above());
      }
   }

   private int treePlacementCellSize() {
      return this.settings.customTrees() ? PROCEDURAL_TREE_CELL_SIZE : 5;
   }

   private TellusProceduralTreeGenerator.TreeAnchor treeAnchorForCell(int cellX, int cellZ, long worldSeed) {
      if (this.settings.customTrees()) {
         return TellusProceduralTreeGenerator.anchorForCell(cellX, cellZ, worldSeed);
      }

      long seed = seedFromCoords(cellX, 0, cellZ) ^ worldSeed;
      RandomSource random = RandomSource.create(seed);
      return new TellusProceduralTreeGenerator.TreeAnchor(
         cellX * 5 + random.nextInt(5),
         cellZ * 5 + random.nextInt(5),
         seed
      );
   }

   /** The blended land cover trees follow, for columns outside a captured chunk context. */
   private int sampleTreeCoverClass(int worldX, int worldZ) {
      int rawCoverClass = this.sampleCoverClass(worldX, worldZ);
      return this.resolveSurfaceCoverClassForTerrain(rawCoverClass, this.sampleVisualCoverClass(worldX, worldZ, rawCoverClass));
   }

   private boolean shouldPlaceTreesForCover(int coverClass, Holder<Biome> biome, int worldX, int worldZ, long seed) {
      if (coverClass == MountainSurfaceRules.ESA_TREE_COVER) {
         return true;
      } else if (!this.settings.randomBiomes()
         || coverClass == ESA_NO_DATA
         || coverClass == ESA_BUILT_UP
         || coverClass == ESA_WATER
         || coverClass == ESA_MANGROVES
         || !RandomBiomeMixer.isLandPatchActive(this.settings, worldX, worldZ)
         || biome.is(Biomes.MANGROVE_SWAMP)) {
         return false;
      } else {
         return seededRandomInt(seed ^ RANDOM_BIOME_TREE_SALT, 100) < RANDOM_BIOME_TREE_CHANCE;
      }
   }

   private boolean isNearWater(int worldX, int worldZ, int radius) {
      for (int dz = -radius; dz <= radius; dz++) {
         int z = worldZ + dz;

         for (int dx = -radius; dx <= radius; dx++) {
            int x = worldX + dx;
            int coverClass = this.sampleCoverClass(x, z);
            WaterSurfaceResolver.WaterInfo info = this.waterResolver.resolveFastWaterInfo(x, z, coverClass);
            if (info.isWater()) {
               return true;
            }
         }
      }

      return false;
   }

   private static boolean hasWaterNear(boolean[] waterFlags, int localX, int localZ, int radius) {
      for (int z = localZ - radius; z <= localZ + radius; z++) {
         for (int x = localX - radius; x <= localX + radius; x++) {
            if (waterFlags[chunkIndex(x, z)]) {
               return true;
            }
         }
      }

      return false;
   }

   private int sampleSurfaceHeight(int blockX, int blockZ) {
      return this.sampleSurfaceHeight(blockX, blockZ, this.settings.worldScale());
   }

   private UndergroundStructureEnvelope sampleUndergroundStructureEnvelope(BoundingBox box) {
      int lowestSurfaceY = Integer.MAX_VALUE;
      int minX = box.minX();
      int maxX = box.maxX();
      int minZ = box.minZ();
      int maxZ = box.maxZ();

      for (int z = minZ; ; z = Math.min(z + 8, maxZ)) {
         for (int x = minX; ; x = Math.min(x + 8, maxX)) {
            int surfaceY = this.sampleSurfaceHeight(x, z);
            lowestSurfaceY = Math.min(lowestSurfaceY, surfaceY);
            if (x == maxX) {
               break;
            }
         }

         if (z == maxZ) {
            break;
         }
      }

      return new UndergroundStructureEnvelope(lowestSurfaceY);
   }

   private int resolveThinShellSupportBottomY(
      int localX,
      int localZ,
      int worldX,
      int worldZ,
      int surface,
      int supportAnchorY,
      int[] terrainSurfaces,
      int chunkMinY,
      boolean oceanSupport
   ) {
      return TerrainShellBedrockProtection.supportBottomY(surface, this.settings.undergroundDepth(), chunkMinY);
   }

   private int[] computeTerrainShellBedrockSkinTopYs(
      int[] terrainSurfaces,
      int[] heightGrid,
      int gridSize,
      int step,
      int chunkMinY,
      int chunkMaxY
   ) {
      int[] result = new int[CHUNK_AREA];

      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         for (int localX = 0; localX < CHUNK_SIDE; localX++) {
            int index = chunkIndex(localX, localZ);
            int supportBottomY = TerrainShellBedrockProtection.supportBottomY(
               terrainSurfaces[index], this.settings.undergroundDepth(), chunkMinY
            );
            int gridIndex = (localZ + step) * gridSize + localX + step;
            int highestNeighborSupportBottomY = Integer.MIN_VALUE;
            highestNeighborSupportBottomY = Math.max(
               highestNeighborSupportBottomY,
               this.terrainShellNeighborSupportBottomY(localX - 1, localZ, gridIndex - 1, terrainSurfaces, heightGrid, chunkMinY, chunkMaxY)
            );
            highestNeighborSupportBottomY = Math.max(
               highestNeighborSupportBottomY,
               this.terrainShellNeighborSupportBottomY(localX + 1, localZ, gridIndex + 1, terrainSurfaces, heightGrid, chunkMinY, chunkMaxY)
            );
            highestNeighborSupportBottomY = Math.max(
               highestNeighborSupportBottomY,
               this.terrainShellNeighborSupportBottomY(localX, localZ - 1, gridIndex - gridSize, terrainSurfaces, heightGrid, chunkMinY, chunkMaxY)
            );
            highestNeighborSupportBottomY = Math.max(
               highestNeighborSupportBottomY,
               this.terrainShellNeighborSupportBottomY(localX, localZ + 1, gridIndex + gridSize, terrainSurfaces, heightGrid, chunkMinY, chunkMaxY)
            );
            result[index] = TerrainShellBedrockProtection.sideSkinTopY(
               supportBottomY, Mth.clamp(terrainSurfaces[index], chunkMinY, chunkMaxY), highestNeighborSupportBottomY
            );
         }
      }

      return result;
   }

   private int[] computeTerrainShellBedrockCurtainBottomYs(
      int[] terrainSurfaces,
      int[] heightGrid,
      int gridSize,
      int step,
      int chunkMinY,
      int chunkMaxY
   ) {
      int[] result = new int[CHUNK_AREA];

      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         for (int localX = 0; localX < CHUNK_SIDE; localX++) {
            int index = chunkIndex(localX, localZ);
            int supportBottomY = TerrainShellBedrockProtection.supportBottomY(
               terrainSurfaces[index], this.settings.undergroundDepth(), chunkMinY
            );
            int gridIndex = (localZ + step) * gridSize + localX + step;
            int lowestNeighborSurfaceY = Integer.MAX_VALUE;
            lowestNeighborSurfaceY = Math.min(
               lowestNeighborSurfaceY,
               this.terrainShellNeighborSurfaceY(localX - 1, localZ, gridIndex - 1, terrainSurfaces, heightGrid, chunkMinY, chunkMaxY)
            );
            lowestNeighborSurfaceY = Math.min(
               lowestNeighborSurfaceY,
               this.terrainShellNeighborSurfaceY(localX + 1, localZ, gridIndex + 1, terrainSurfaces, heightGrid, chunkMinY, chunkMaxY)
            );
            lowestNeighborSurfaceY = Math.min(
               lowestNeighborSurfaceY,
               this.terrainShellNeighborSurfaceY(localX, localZ - 1, gridIndex - gridSize, terrainSurfaces, heightGrid, chunkMinY, chunkMaxY)
            );
            lowestNeighborSurfaceY = Math.min(
               lowestNeighborSurfaceY,
               this.terrainShellNeighborSurfaceY(localX, localZ + 1, gridIndex + gridSize, terrainSurfaces, heightGrid, chunkMinY, chunkMaxY)
            );
            result[index] = TerrainShellBedrockProtection.voidCurtainBottomY(
               supportBottomY, chunkMinY, lowestNeighborSurfaceY
            );
         }
      }

      return result;
   }

   private int terrainShellNeighborSupportBottomY(
      int localX,
      int localZ,
      int gridIndex,
      int[] terrainSurfaces,
      int[] heightGrid,
      int chunkMinY,
      int chunkMaxY
   ) {
      int surface = this.terrainShellNeighborSurfaceY(
         localX, localZ, gridIndex, terrainSurfaces, heightGrid, chunkMinY, chunkMaxY
      );
      return TerrainShellBedrockProtection.supportBottomY(surface, this.settings.undergroundDepth(), chunkMinY);
   }

   private int terrainShellNeighborSurfaceY(
      int localX,
      int localZ,
      int gridIndex,
      int[] terrainSurfaces,
      int[] heightGrid,
      int chunkMinY,
      int chunkMaxY
   ) {
      int surface = localX >= 0 && localX < CHUNK_SIDE && localZ >= 0 && localZ < CHUNK_SIDE
         ? terrainSurfaces[chunkIndex(localX, localZ)]
         : heightGrid[gridIndex];
      return Mth.clamp(surface, chunkMinY, chunkMaxY);
   }

   private void applyTerrainShellBedrockSkin(
      ChunkPos pos,
      int[] terrainSurfaces,
      int[] skinTopYs,
      int[] curtainBottomYs,
      int chunkMinY,
      EarthChunkGenerator.TerrainShellBedrockWriter writer
   ) {
      int chunkMinX = pos.getMinBlockX();
      int chunkMinZ = pos.getMinBlockZ();
      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         for (int localX = 0; localX < CHUNK_SIDE; localX++) {
            int index = chunkIndex(localX, localZ);
            int supportBottomY = TerrainShellBedrockProtection.supportBottomY(
               terrainSurfaces[index], this.settings.undergroundDepth(), chunkMinY
            );
            int skinTopY = skinTopYs[index];
            if (skinTopY > supportBottomY) {
               for (int y = supportBottomY + 1; ; y++) {
                  writer.setBlock(localX, localZ, chunkMinX + localX, chunkMinZ + localZ, y);
                  if (y == skinTopY) {
                     break;
                  }
               }
            }
            int curtainBottomY = curtainBottomYs[index];
            if (curtainBottomY < supportBottomY) {
               for (int y = curtainBottomY; y < supportBottomY; y++) {
                  writer.setBlock(localX, localZ, chunkMinX + localX, chunkMinZ + localZ, y);
               }
            }
         }
      }
   }

   @FunctionalInterface
   private interface TerrainShellBedrockWriter {
      void setBlock(int localX, int localZ, int worldX, int worldZ, int y);
   }

   private int sampleSurfaceHeightLocalOnly(int blockX, int blockZ) {
      return this.sampleSurfaceHeightLocalOnly(blockX, blockZ, this.settings.worldScale());
   }

   private int sampleSurfaceHeightMemoryOnly(int blockX, int blockZ) {
      return this.sampleSurfaceHeightMemoryOnly(blockX, blockZ, this.settings.worldScale());
   }

   private TerrainPreloadPackage.Sample samplePreloadedTerrain(int blockX, int blockZ) {
      return this.preloadedTerrain.sample(blockX, blockZ, this.settings.worldScale());
   }

   private TerrainPreloadPackage.Sample samplePreloadedTerrain(int blockX, int blockZ, double previewResolutionMeters) {
      return this.preloadedTerrain.sample(blockX, blockZ, previewResolutionMeters);
   }

   private int samplePreloadedCoverClass(int blockX, int blockZ) {
      TerrainPreloadPackage.Sample preloaded = this.samplePreloadedTerrain(blockX, blockZ);
      return preloaded == null ? Integer.MIN_VALUE : preloaded.coverClass();
   }

   private int samplePreloadedCoverClass(int blockX, int blockZ, double previewResolutionMeters) {
      TerrainPreloadPackage.Sample preloaded = this.samplePreloadedTerrain(blockX, blockZ, previewResolutionMeters);
      return preloaded == null ? Integer.MIN_VALUE : preloaded.coverClass();
   }

   private int sampleSurfaceHeight(int blockX, int blockZ, double previewResolutionMeters) {
      TerrainPreloadPackage.Sample preloaded = this.samplePreloadedTerrain(blockX, blockZ, previewResolutionMeters);
      if (preloaded != null) {
         return preloaded.terrainHeight();
      }

      boolean oceanZoom = this.useOceanZoom(blockX, blockZ, previewResolutionMeters);
      double elevation = ELEVATION_SOURCE.samplePreviewElevationMeters(
         blockX, blockZ, this.settings.worldScale(), oceanZoom, this.settings.demSelection(), previewResolutionMeters
      );
      return this.scaleElevationToHeight(elevation, blockZ);
   }

   private int sampleSurfaceHeightLocalOnly(int blockX, int blockZ, double previewResolutionMeters) {
      TerrainPreloadPackage.Sample preloaded = this.samplePreloadedTerrain(blockX, blockZ, previewResolutionMeters);
      if (preloaded != null) {
         return preloaded.terrainHeight();
      }

      boolean oceanZoom = this.useOceanZoomLocalOnly(blockX, blockZ, previewResolutionMeters);
      double elevation = ELEVATION_SOURCE.samplePreviewElevationMetersLocalOnly(
         blockX, blockZ, this.settings.worldScale(), oceanZoom, this.settings.demSelection(), previewResolutionMeters
      );
      return this.scaleElevationToHeight(elevation, blockZ);
   }

   private int sampleSurfaceHeightMemoryOnly(int blockX, int blockZ, double previewResolutionMeters) {
      TerrainPreloadPackage.Sample preloaded = this.samplePreloadedTerrain(blockX, blockZ, previewResolutionMeters);
      if (preloaded != null) {
         return preloaded.terrainHeight();
      }

      boolean oceanZoom = this.useOceanZoomMemoryOnly(blockX, blockZ, previewResolutionMeters);
      double elevation = ELEVATION_SOURCE.samplePreviewElevationMetersMemoryOnly(
         blockX, blockZ, this.settings.worldScale(), oceanZoom, this.settings.demSelection(), previewResolutionMeters
      );
      if (Double.isNaN(elevation)) {
         return Integer.MIN_VALUE;
      } else {
         return this.scaleElevationToHeight(elevation, blockZ);
      }
   }

   private int scaleElevationToHeight(double elevation, double blockZ) {
      int height = TerrainHeightTransform.blockOffset(
         elevation,
         blockZ,
         this.settings.effectiveVerticalWorldScale(),
         this.settings.effectiveTerrestrialHeightScale(),
         this.settings.effectiveOceanicHeightScale(),
         this.settings.experimentalIncreaseHeight(),
         this.settings.automaticHeightScaling()
      );
      int offset = this.settings.effectiveHeightOffset();
      return height + offset;
   }

   private boolean useOceanZoom(double blockX, double blockZ, double previewResolutionMeters) {
      if (this.settings.enableWater()) {
         return false;
      }
      TellusLandMaskSource.LandMaskSample landSample = LAND_MASK_SOURCE.sampleLandMask(blockX, blockZ, this.settings.worldScale());
      if (!landSample.known()) {
         return false;
      } else if (landSample.land()) {
         return false;
      } else {
         int coverClass = LAND_COVER_SOURCE.sampleCoverClass(blockX, blockZ, this.settings.worldScale(), previewResolutionMeters);
         return coverClass == 0 || coverClass == 80;
      }
   }

   private boolean useOceanZoomLocalOnly(double blockX, double blockZ, double previewResolutionMeters) {
      if (this.settings.enableWater()) {
         return false;
      }
      TellusLandMaskSource.LandMaskSample landSample = LAND_MASK_SOURCE.sampleLandMaskLocalOnly(blockX, blockZ, this.settings.worldScale());
      if (!landSample.known()) {
         return false;
      } else if (landSample.land()) {
         return false;
      } else {
         int coverClass = LAND_COVER_SOURCE.sampleCoverClassLocalOnly(blockX, blockZ, this.settings.worldScale(), previewResolutionMeters);
         return coverClass == 0 || coverClass == 80;
      }
   }

   private boolean useOceanZoomMemoryOnly(double blockX, double blockZ, double previewResolutionMeters) {
      if (this.settings.enableWater()) {
         return false;
      }
      TellusLandMaskSource.LandMaskSample landSample = LAND_MASK_SOURCE.sampleLandMaskLocalOnly(blockX, blockZ, this.settings.worldScale());
      if (!landSample.known()) {
         return false;
      } else if (landSample.land()) {
         return false;
      } else {
         int coverClass = LAND_COVER_SOURCE.sampleCoverClassMemoryOnly(blockX, blockZ, this.settings.worldScale(), previewResolutionMeters);
         return coverClass == Integer.MIN_VALUE || coverClass == 0 || coverClass == 80;
      }
   }

   private int sampleCoverClassForBaseTerrain(int worldX, int worldZ) {
      int preloaded = this.samplePreloadedCoverClass(worldX, worldZ);
      if (preloaded != Integer.MIN_VALUE) {
         return preloaded;
      }

      return LAND_COVER_SOURCE.sampleCoverClassLocalOnly(worldX, worldZ, this.settings.worldScale(), this.settings.worldScale());
   }

   private int sampleCoverClassForExactTerrain(int worldX, int worldZ) {
      int preloaded = this.samplePreloadedCoverClass(worldX, worldZ);
      if (preloaded != Integer.MIN_VALUE) {
         return preloaded;
      }

      return NON_BLOCKING_TERRAIN_INPUTS
         ? this.sampleCoverClassForBaseTerrain(worldX, worldZ)
         : LAND_COVER_SOURCE.sampleCoverClass(worldX, worldZ, this.settings.worldScale());
   }

   private int sampleCoverClassForTerrainShell(int worldX, int worldZ) {
      int preloaded = this.samplePreloadedCoverClass(worldX, worldZ);
      if (preloaded != Integer.MIN_VALUE) {
         return preloaded;
      }

      return LAND_COVER_SOURCE.sampleCoverClassMemoryOnly(worldX, worldZ, this.settings.worldScale(), this.settings.worldScale());
   }

   private int sampleVisualCoverClassForBaseTerrain(int worldX, int worldZ, int rawCoverClass) {
      double worldScale = this.settings.worldScale();
      if (!shouldSampleVisualCover(worldScale, worldScale, rawCoverClass)) {
         return rawCoverClass;
      }
      int visualCoverClass = LAND_COVER_SOURCE.sampleVisualCoverClassLocalOnly(
         worldX, worldZ, worldScale, worldScale
      );
      return visualCoverClass == Integer.MIN_VALUE ? rawCoverClass : visualCoverClass;
   }

   private int sampleVisualCoverClassForExactTerrain(int worldX, int worldZ, int rawCoverClass) {
      return NON_BLOCKING_TERRAIN_INPUTS
         ? this.sampleVisualCoverClassForBaseTerrain(worldX, worldZ, rawCoverClass)
         : this.sampleVisualCoverClass(worldX, worldZ, rawCoverClass);
   }

   private int sampleVisualCoverClassForTerrainShell(int worldX, int worldZ, int rawCoverClass) {
      double worldScale = this.settings.worldScale();
      if (!shouldSampleVisualCover(worldScale, worldScale, rawCoverClass)) {
         return rawCoverClass;
      }
      int visualCoverClass = LAND_COVER_SOURCE.sampleVisualCoverClassMemoryOnly(
         worldX, worldZ, worldScale, worldScale
      );
      return visualCoverClass == Integer.MIN_VALUE ? rawCoverClass : visualCoverClass;
   }

   private int sampleSlopeDiff(int worldX, int worldZ, int surface) {
      return this.sampleSlopeDiff(worldX, worldZ, surface, this.settings.worldScale());
   }

   private int sampleSlopeDiff(int worldX, int worldZ, int surface, double previewResolutionMeters) {
      int step = 4;
      int east = this.sampleSurfaceHeight(worldX + step, worldZ, previewResolutionMeters);
      int west = this.sampleSurfaceHeight(worldX - step, worldZ, previewResolutionMeters);
      int north = this.sampleSurfaceHeight(worldX, worldZ - step, previewResolutionMeters);
      int south = this.sampleSurfaceHeight(worldX, worldZ + step, previewResolutionMeters);
      return Math.max(Math.max(Math.abs(east - surface), Math.abs(west - surface)), Math.max(Math.abs(north - surface), Math.abs(south - surface)));
   }

   private int sampleConvexity(int worldX, int worldZ, int surface) {
      int step = 4;
      int east = this.sampleSurfaceHeight(worldX + step, worldZ);
      int west = this.sampleSurfaceHeight(worldX - step, worldZ);
      int north = this.sampleSurfaceHeight(worldX, worldZ - step);
      int south = this.sampleSurfaceHeight(worldX, worldZ + step);
      int neighborAverage = (east + west + north + south) / 4;
      return neighborAverage - surface;
   }

   private EarthChunkGenerator.ColumnHeights resolveColumnHeights(
      int worldX,
      int worldZ,
      int localX,
      int localZ,
      int minY,
      int maxYExclusive,
      int coverClass,
      WaterSurfaceResolver.WaterChunkData waterData,
      int cachedSurface
   ) {
      int maxY = Math.max(minY, maxYExclusive - 1);
      if (coverClass == 95) {
         if (this.settings.enableWater()) {
            WaterSurfaceResolver.WaterColumnData merged = this.mergeMangroveWaterColumn(
               worldX,
               worldZ,
               new WaterSurfaceResolver.WaterColumnData(
                  waterData.hasWater(localX, localZ),
                  waterData.isOcean(localX, localZ),
                  waterData.terrainSurface(localX, localZ),
                  waterData.waterSurface(localX, localZ)
               )
            );
            return this.columnHeightsFromWaterColumn(merged, minY, maxY);
         } else {
            int surface = cachedSurface == Integer.MIN_VALUE ? this.sampleSurfaceHeight(worldX, worldZ) : cachedSurface;
            surface = Mth.clamp(surface, minY, maxY);
            int waterSurface = this.resolveMangroveWaterSurface(worldX, worldZ, maxY);
            boolean hasWater = waterSurface > surface;
            return new EarthChunkGenerator.ColumnHeights(surface, waterSurface, hasWater);
         }
      } else {
         int surface = Mth.clamp(cachedSurface == Integer.MIN_VALUE || waterData.isInlandBank(localX, localZ)
            ? waterData.terrainSurface(localX, localZ) : cachedSurface, minY, maxY);
         int waterSurface = Mth.clamp(waterData.waterSurface(localX, localZ), minY, maxY);
         boolean hasWater = waterData.hasWater(localX, localZ);
         int minimumTerrainY = hasWater && waterData.isOcean(localX, localZ)
            ? Math.min(maxY, minY + WaterSurfaceResolver.oceanFloorSupportBlocks())
            : minY;
         return !hasWater
            ? new EarthChunkGenerator.ColumnHeights(surface, surface, false)
            : new EarthChunkGenerator.ColumnHeights(
               Mth.clamp(waterData.terrainSurface(localX, localZ), minimumTerrainY, maxY), waterSurface, true
            );
      }
   }

   private EarthChunkGenerator.ColumnHeights resolveFastColumnHeights(int worldX, int worldZ, int minY, int maxYExclusive, int coverClass) {
      int maxY = Math.max(minY, maxYExclusive - 1);
      if (this.settings.enableWater()) {
         WaterSurfaceResolver.WaterColumnData column = this.normalizeResolvedWaterColumn(
            worldX,
            worldZ,
            coverClass,
            minY,
            maxY,
            this.resolveOsmWaterColumn(worldX, worldZ, coverClass, false)
         );
         return this.columnHeightsFromWaterColumn(column, minY, maxY);
      } else {
         int effectiveCoverClass = this.resolveEffectiveCoverClassForTerrain(coverClass);
         WaterSurfaceResolver.WaterColumnData column = this.waterResolver.resolveColumnData(worldX, worldZ);
         int surface = Mth.clamp(column.terrainSurface(), minY, maxY);
         surface = this.repairAnomalousSurfaceHeight(worldX, worldZ, surface, effectiveCoverClass, minY, maxY, column.hasWater());
         int waterSurface = Mth.clamp(column.waterSurface(), minY, maxY);
         if (!column.hasWater()) {
            return new EarthChunkGenerator.ColumnHeights(surface, surface, false);
         } else {
            if (surface >= waterSurface) {
               surface = Math.max(minY, waterSurface - 1);
            }

            return new EarthChunkGenerator.ColumnHeights(surface, waterSurface, true);
         }
      }
   }

   private int resolveMangroveWaterSurface(int worldX, int worldZ, int maxY) {
      long seed = seedFromCoords(worldX, 1, worldZ) ^ -7046029254386353131L;
      int offset = 1 + seededRandomInt(seed, 3);
      int waterTop = Math.min(this.seaLevel, maxY);
      return Math.min(waterTop, this.seaLevel - offset);
   }

   private static long seedFromCoords(int x, int y, int z) {
      long seed = x * 3129871 ^ z * 116129781L ^ y;
      seed = seed * seed * 42317861L + seed * 11L;
      return seed >> 16;
   }

   // Reproduces new Random(seed).nextInt(bound) without allocating a Random.
   private static int seededRandomInt(long seed, int bound) {
      if (bound <= 0) {
         throw new IllegalArgumentException("bound must be positive");
      }

      long state = (seed ^ JAVA_RANDOM_MULTIPLIER) & JAVA_RANDOM_MASK;
      if ((bound & -bound) == bound) {
         state = nextJavaRandomState(state);
         return (int)(((long)bound * (long)((int)(state >>> 17))) >> 31);
      }

      int bits;
      int value;
      do {
         state = nextJavaRandomState(state);
         bits = (int)(state >>> 17);
         value = bits % bound;
      } while (bits - value + (bound - 1) < 0);

      return value;
   }

	   private static long nextJavaRandomState(long state) {
	      return (state * JAVA_RANDOM_MULTIPLIER + JAVA_RANDOM_ADDEND) & JAVA_RANDOM_MASK;
	   }

   private void applyThinShellSurface(
      ChunkAccess chunk,
      MutableBlockPos cursor,
      int worldX,
      int worldZ,
      int surface,
      int minY,
      boolean underwater,
      boolean snowCovered,
      Holder<Biome> biome,
      int slopeDiff,
      int convexity,
      int coverClass,
      EarthChunkGenerator.FullChunkOceanBeachCache oceanBeachCache,
      EarthChunkGenerator.SurfaceApplyProfiler profiler,
      boolean useFastSurfacePalette
   ) {
      if (surface >= minY) {
         long paletteResolveStartNs = beginFullChunkProfiling();
         EarthChunkGenerator.SurfacePalette palette = this.selectFullChunkSurfacePalette(
            biome, worldX, worldZ, surface, underwater, slopeDiff, convexity, coverClass, oceanBeachCache, profiler, useFastSurfacePalette
         );
         profiler.paletteResolveNs += elapsedFullChunkProfilingSince(paletteResolveStartNs);
         BlockState top = this.resolveThinShellTopBlock(palette, surface, underwater);
         top = ThinShellSurfaceOre.resolve(top, this.settings.oreDistribution(), underwater, snowCovered, worldX, surface, worldZ);
         long blockWriteStartNs = beginFullChunkProfiling();
         if (palette != null && !underwater && biome.is(BiomeTags.IS_BADLANDS) && slopeDiff >= 3) {
            long badlandsStartNs = beginFullChunkProfiling();
            this.applyBadlandsBands(chunk, cursor, worldX, worldZ, surface, minY, palette, slopeDiff, true);
            profiler.badlandsNs += elapsedFullChunkProfilingSince(badlandsStartNs);
         } else if (palette != null) {
            int depth = this.resolveSurfaceMaterialDepth(palette, top, worldX, worldZ);
            int bottom = this.thinShellSurfaceBottomY(minY, surface, depth);
            fillSurfaceMaterialColumn(chunk, cursor, worldX, worldZ, surface, bottom, top, palette.filler());
         } else {
            cursor.set(worldX, surface, worldZ);
            setChunkBlockStateDiscardingBlockEntity(chunk, cursor, top);
         }
         profiler.blockWriteNs += elapsedFullChunkProfilingSince(blockWriteStartNs);
      }
   }

   private void applyThinShellSurface(
      EarthChunkGenerator.ChunkSectionWriter writer,
      int worldX,
      int worldZ,
      int localX,
      int localZ,
      int surface,
      int minY,
      boolean underwater,
      boolean snowCovered,
      Holder<Biome> biome,
      int slopeDiff,
      int convexity,
      int coverClass,
      EarthChunkGenerator.FullChunkOceanBeachCache oceanBeachCache,
      EarthChunkGenerator.SurfaceApplyProfiler profiler,
      boolean useFastSurfacePalette
   ) {
      if (surface >= minY) {
         long paletteResolveStartNs = beginFullChunkProfiling();
         EarthChunkGenerator.SurfacePalette palette = this.selectFullChunkSurfacePalette(
            biome, worldX, worldZ, surface, underwater, slopeDiff, convexity, coverClass, oceanBeachCache, profiler, useFastSurfacePalette
         );
         profiler.paletteResolveNs += elapsedFullChunkProfilingSince(paletteResolveStartNs);
         BlockState top = this.resolveThinShellTopBlock(palette, surface, underwater);
         top = ThinShellSurfaceOre.resolve(top, this.settings.oreDistribution(), underwater, snowCovered, worldX, surface, worldZ);
         long blockWriteStartNs = beginFullChunkProfiling();
         if (palette != null && !underwater && biome.is(BiomeTags.IS_BADLANDS) && slopeDiff >= 3) {
            long badlandsStartNs = beginFullChunkProfiling();
            this.applyBadlandsBands(writer, localX, localZ, worldX, worldZ, surface, minY, palette, slopeDiff, true);
            profiler.badlandsNs += elapsedFullChunkProfilingSince(badlandsStartNs);
         } else if (palette != null) {
            int depth = this.resolveSurfaceMaterialDepth(palette, top, worldX, worldZ);
            int bottom = this.thinShellSurfaceBottomY(minY, surface, depth);
            fillSurfaceMaterialColumn(writer, localX, localZ, surface, bottom, top, palette.filler());
         } else {
            writer.setBlock(localX, localZ, surface, top);
         }
         profiler.blockWriteNs += elapsedFullChunkProfilingSince(blockWriteStartNs);
      }
   }

   private BlockState resolveThinShellTopBlock(EarthChunkGenerator.SurfacePalette palette, int surface, boolean underwater) {
      if (palette == null) {
         return surface < this.minY + 64 ? DEEPSLATE_STATE : STONE_STATE;
      }

      return underwater ? palette.underwaterTop() : palette.top();
   }

   private int thinShellSurfaceBottomY(int minY, int surface, int depth) {
      int supportBottomY = Math.max(minY, surface - this.settings.undergroundDepth());
      int surfaceBottomY = surfaceMaterialBottomY(minY, surface, depth);
      return Math.min(surface, Math.max(supportBottomY + 1, surfaceBottomY));
   }

   private void applySurface(
      ChunkAccess chunk,
      MutableBlockPos cursor,
      int worldX,
      int worldZ,
      int surface,
      int minY,
      boolean underwater,
      Holder<Biome> biome,
      int slopeDiff,
      int convexity,
      int coverClass,
      EarthChunkGenerator.FullChunkOceanBeachCache oceanBeachCache,
      EarthChunkGenerator.SurfaceApplyProfiler profiler,
      boolean useFastSurfacePalette
   ) {
      if (surface >= minY) {
         long paletteResolveStartNs = beginFullChunkProfiling();
         EarthChunkGenerator.SurfacePalette palette = this.selectFullChunkSurfacePalette(
            biome, worldX, worldZ, surface, underwater, slopeDiff, convexity, coverClass, oceanBeachCache, profiler, useFastSurfacePalette
         );
         profiler.paletteResolveNs += elapsedFullChunkProfilingSince(paletteResolveStartNs);
         if (palette != null) {
            if (!underwater && biome.is(BiomeTags.IS_BADLANDS) && slopeDiff >= 3) {
               long badlandsStartNs = beginFullChunkProfiling();
               this.applyBadlandsBands(chunk, cursor, worldX, worldZ, surface, minY, palette, slopeDiff, false);
               profiler.badlandsNs += elapsedFullChunkProfilingSince(badlandsStartNs);
            } else {
               BlockState top = underwater ? palette.underwaterTop() : palette.top();
               BlockState filler = palette.filler();
               int depth = this.resolveSurfaceMaterialDepth(palette, top, worldX, worldZ);
               int bottom = surfaceMaterialBottomY(minY, surface, depth);

               long blockWriteStartNs = beginFullChunkProfiling();
               fillSurfaceMaterialColumn(chunk, cursor, worldX, worldZ, surface, bottom, top, filler);
               profiler.blockWriteNs += elapsedFullChunkProfilingSince(blockWriteStartNs);
            }
         }
      }
   }

   private void applySurface(
      EarthChunkGenerator.ChunkSectionWriter writer,
      int worldX,
      int worldZ,
      int localX,
      int localZ,
      int surface,
      int minY,
      boolean underwater,
      Holder<Biome> biome,
      int slopeDiff,
      int convexity,
      int coverClass,
      EarthChunkGenerator.FullChunkOceanBeachCache oceanBeachCache,
      EarthChunkGenerator.SurfaceApplyProfiler profiler,
      boolean useFastSurfacePalette
   ) {
      if (surface >= minY) {
         long paletteResolveStartNs = beginFullChunkProfiling();
         EarthChunkGenerator.SurfacePalette palette = this.selectFullChunkSurfacePalette(
            biome, worldX, worldZ, surface, underwater, slopeDiff, convexity, coverClass, oceanBeachCache, profiler, useFastSurfacePalette
         );
         profiler.paletteResolveNs += elapsedFullChunkProfilingSince(paletteResolveStartNs);
         if (palette != null) {
            if (!underwater && biome.is(BiomeTags.IS_BADLANDS) && slopeDiff >= 3) {
               long badlandsStartNs = beginFullChunkProfiling();
               this.applyBadlandsBands(writer, localX, localZ, worldX, worldZ, surface, minY, palette, slopeDiff, false);
               profiler.badlandsNs += elapsedFullChunkProfilingSince(badlandsStartNs);
            } else {
               BlockState top = underwater ? palette.underwaterTop() : palette.top();
               BlockState filler = palette.filler();
               int depth = this.resolveSurfaceMaterialDepth(palette, top, worldX, worldZ);
               int bottom = surfaceMaterialBottomY(minY, surface, depth);

               long blockWriteStartNs = beginFullChunkProfiling();
               fillSurfaceMaterialColumn(writer, localX, localZ, surface, bottom, top, filler);
               profiler.blockWriteNs += elapsedFullChunkProfilingSince(blockWriteStartNs);
            }
         }
      }
   }

   private EarthChunkGenerator.SurfacePalette selectFullChunkSurfacePalette(
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int slopeDiff,
      int convexity,
      int coverClass,
      EarthChunkGenerator.FullChunkOceanBeachCache oceanBeachCache,
      EarthChunkGenerator.SurfaceApplyProfiler profiler,
      boolean useFastSurfacePalette
   ) {
      boolean snowLikeTerrain = !underwater && this.isAntarcticSnowTerrain(coverClass, worldZ);
      EarthChunkGenerator.SurfacePalette palette = this.selectBaseSurfacePalette(biome, worldX, worldZ, surface, coverClass);
      if (palette == null) {
         return null;
      } else {
         palette = this.applyFullChunkOceanBeachPaletteOverride(
            palette, biome, worldX, worldZ, surface, underwater, slopeDiff, convexity, coverClass, oceanBeachCache
         );
         if (palette == null) {
            return null;
         } else {
            EarthChunkGenerator.SurfacePalette resolved;
            if (useFastSurfacePalette && (underwater || preservesBiomeSurfacePalette(biome))) {
               profiler.fastPathCount++;
               resolved = palette;
            } else {
               // Real chunks and both LOD paths share one slope and landform rule, so a
               // hillside keeps its look when real chunks replace its LODs.
               resolved = this.applySlopeSurfaceOverride(
                  palette, underwater, slopeDiff, convexity, coverClass, biome, worldX, worldZ, surface, null, snowLikeTerrain
               );
            }

            EarthChunkGenerator.SurfacePalette sandResolved = this.applyOvertureSandPaletteOverride(
               resolved, worldX, worldZ, underwater
            );
            EarthChunkGenerator.SurfacePalette deepslateResolved = this.applyDemDeepslateSlopePaletteOverride(
               sandResolved, biome, worldX, worldZ, underwater, slopeDiff
            );
            return applyBadlandsCliffPalette(deepslateResolved, biome, worldX, worldZ, surface, underwater, slopeDiff);
         }
      }
   }

   private EarthChunkGenerator.SurfacePalette applyFullChunkOceanBeachPaletteOverride(
      EarthChunkGenerator.SurfacePalette palette,
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int slopeDiff,
      int convexity,
      int coverClass,
      EarthChunkGenerator.FullChunkOceanBeachCache oceanBeachCache
   ) {
      if (underwater || oceanBeachCache == null || !oceanBeachCache.hasOcean() || !qualifiesForOceanBeachOverride(coverClass)) {
         return palette;
      } else {
         int distanceToOcean = oceanBeachCache.distanceToOcean(worldX, worldZ);
         if (distanceToOcean < 0) {
            return palette;
         } else {
            MountainSurfaceRules.ShorelineMaterial material = MountainSurfaceRules.classifyShorelineMaterial(
               coverClass,
               climateGroupForBiome(biome),
               this.mountainZoneHeight(surface, worldX, worldZ),
               slopeDiff,
               convexity,
               MountainSurfaceRules.ShorelineKind.OCEAN,
               distanceToOcean,
               biome.is(BiomeTags.IS_BADLANDS)
            );
            return switch (material) {
               case NONE, PRESERVE_WETLAND -> palette;
               case SAND, RED_SAND -> palette;
               case GRAVEL -> EarthChunkGenerator.SurfacePalette.gravelly();
            };
         }
      }
   }

   private static boolean qualifiesForOceanBeachOverride(int coverClass) {
      return coverClass != MountainSurfaceRules.ESA_WATER
         && coverClass != MountainSurfaceRules.ESA_BUILT
         && coverClass != MountainSurfaceRules.ESA_SNOW_ICE
         && coverClass != MountainSurfaceRules.ESA_WETLAND
         && coverClass != MountainSurfaceRules.ESA_MANGROVES;
   }

   private void applyBadlandsBands(
      ChunkAccess chunk,
      MutableBlockPos cursor,
      int worldX,
      int worldZ,
      int surface,
      int minY,
      EarthChunkGenerator.SurfacePalette palette,
      int localReliefBlocks,
      boolean thinShell
   ) {
      int depth = BadlandsTerrainPolicy.cliffBandDepth(palette.depth(), localReliefBlocks);
      int bottom = thinShell
         ? this.thinShellSurfaceBottomY(minY, surface, depth)
         : surfaceMaterialBottomY(minY, surface, depth);
      BlockState top = palette.top();

      for (int y = surface; y >= bottom; y--) {
         cursor.set(worldX, y, worldZ);
         BlockState state = y == surface ? top : badlandsBand(worldX, worldZ, y);
         setChunkBlockStateDiscardingBlockEntity(chunk, cursor, state);
      }
   }

   private void applyBadlandsBands(
      EarthChunkGenerator.ChunkSectionWriter writer,
      int localX,
      int localZ,
      int worldX,
      int worldZ,
      int surface,
      int minY,
      EarthChunkGenerator.SurfacePalette palette,
      int localReliefBlocks,
      boolean thinShell
   ) {
      int depth = BadlandsTerrainPolicy.cliffBandDepth(palette.depth(), localReliefBlocks);
      int bottom = thinShell
         ? this.thinShellSurfaceBottomY(minY, surface, depth)
         : surfaceMaterialBottomY(minY, surface, depth);
      writer.fillBadlandsBands(localX, localZ, worldX, worldZ, surface, bottom, palette.top());
   }

   private static int surfaceMaterialBottomY(int minY, int surface, int depth) {
      // Bedrock is placed at minY during terrain fill; keep falling fillers one block above it.
      return Math.max(minY + 1, surface - depth + 1);
   }

   private int resolveSurfaceMaterialDepth(
      EarthChunkGenerator.SurfacePalette palette, BlockState top, int worldX, int worldZ
   ) {
      return usesNaturalDepthVariation(top, palette.filler())
         ? SurfaceMaterialDepthPolicy.naturalDepth(palette.depth(), worldX, worldZ, this.worldSeed)
         : palette.depth();
   }

   private static boolean usesNaturalDepthVariation(BlockState top, BlockState filler) {
      return isSoilBlock(top)
         || isSoilBlock(filler)
         || top.is(Blocks.SAND)
         || top.is(Blocks.RED_SAND)
         || filler.is(Blocks.SAND)
         || filler.is(Blocks.RED_SAND);
   }

   private static void fillSurfaceMaterialColumn(
      ChunkAccess chunk,
      MutableBlockPos cursor,
      int worldX,
      int worldZ,
      int surface,
      int bottom,
      BlockState top,
      BlockState filler
   ) {
      BlockState shallowFiller = shallowSurfaceFiller(top, filler);
      for (int y = surface; y >= bottom; y--) {
         BlockState state = y == surface ? top : y == bottom ? filler : shallowFiller;
         cursor.set(worldX, y, worldZ);
         setChunkBlockStateDiscardingBlockEntity(chunk, cursor, state);
      }
   }

   private static void fillSurfaceMaterialColumn(
      EarthChunkGenerator.ChunkSectionWriter writer,
      int localX,
      int localZ,
      int surface,
      int bottom,
      BlockState top,
      BlockState filler
   ) {
      BlockState shallowFiller = shallowSurfaceFiller(top, filler);
      writer.fillSurfaceColumn(localX, localZ, surface, bottom, top, shallowFiller);
      if (bottom < surface && !shallowFiller.equals(filler)) {
         writer.setBlock(localX, localZ, bottom, filler);
      }
   }

   private static BlockState shallowSurfaceFiller(BlockState top, BlockState filler) {
      if (top.is(Blocks.SAND) && filler.is(Blocks.SANDSTONE)
         || top.is(Blocks.RED_SAND) && filler.is(Blocks.RED_SANDSTONE)) {
         return top;
      }
      return filler;
   }

   private static BlockState badlandsBand(int worldX, int worldZ, int y) {
      return BADLANDS_BANDS[BadlandsTerrainPolicy.bandMaterialIndex(worldX, worldZ, y)];
   }

   private static BlockState badlandsPlateauTop(int worldX, int worldZ) {
      return switch (BadlandsTerrainPolicy.plateauMaterialIndex(worldX, worldZ)) {
         case BadlandsTerrainPolicy.PLATEAU_COARSE_DIRT -> COARSE_DIRT_STATE;
         case BadlandsTerrainPolicy.PLATEAU_TERRACOTTA -> TERRACOTTA_STATE;
         case BadlandsTerrainPolicy.PLATEAU_BROWN_TERRACOTTA -> BADLANDS_BANDS[BadlandsTerrainPolicy.BROWN_TERRACOTTA];
         default -> RED_SAND_STATE;
      };
   }

   private static EarthChunkGenerator.SurfacePalette applyBadlandsCliffPalette(
      EarthChunkGenerator.SurfacePalette palette,
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int localReliefBlocks
   ) {
      if (palette == null || underwater || localReliefBlocks < 3 || !biome.is(BiomeTags.IS_BADLANDS)) {
         return palette;
      }

      return new EarthChunkGenerator.SurfacePalette(
         badlandsBand(worldX, worldZ, surface),
         palette.underwaterTop(),
         badlandsBand(worldX, worldZ, surface - 1),
         BadlandsTerrainPolicy.cliffBandDepth(palette.depth(), localReliefBlocks)
      );
   }


   public BlockState resolveLodSurfaceBlock(int worldX, int worldZ, int surface, boolean underwater) {
      if (this.biomeSource instanceof EarthBiomeSource earthBiomeSource) {
         Holder<Biome> biome = earthBiomeSource.getBiomeAtBlock(worldX, worldZ);
         return this.resolveLodSurface(biome, worldX, worldZ, surface, underwater).top();
      } else {
         return STONE_STATE;
      }
   }


   public BlockState resolveLodFillerBlock(int worldX, int worldZ, int surface, boolean underwater) {
      if (this.biomeSource instanceof EarthBiomeSource earthBiomeSource) {
         Holder<Biome> biome = earthBiomeSource.getBiomeAtBlock(worldX, worldZ);
         return this.resolveLodSurface(biome, worldX, worldZ, surface, underwater).filler();
      } else {
         return STONE_STATE;
      }
   }

   public boolean hasOvertureSandAt(int worldX, int worldZ) {
      return this.hasOvertureSandAt(worldX, worldZ, this.resolveFullChunkOsmQueryMode());
   }

   public boolean hasOvertureSandAt(int worldX, int worldZ, OsmQueryMode queryMode) {
      Double lodResolutionMeters = this.lodSandResolutionMeters.get();
      return lodResolutionMeters != null
         ? OSM_SAND_SOURCE.containsSand(worldX, worldZ, this.settings.worldScale(), lodResolutionMeters, queryMode)
         : OSM_SAND_SOURCE.containsSand(worldX, worldZ, this.settings.worldScale(), queryMode);
   }

   public double sampleDemSlopeDegrees(int worldX, int worldZ) {
      return this.sampleDemGradient(worldX, worldZ).slopeDegrees();
   }

   /** {@link #sampleDemSlopeDegrees} with the way the ground falls. */
   private TerrainSlopePolicy.Gradient sampleDemGradient(int worldX, int worldZ) {
      Double lodResolutionMeters = this.lodSlopeResolutionMeters.get();
      if (lodResolutionMeters != null) {
         // A distant LOD column reads slope from the DEM tiles its own terrain used, rather than
         // fetching native-resolution tiles for every column across its footprint.
         return ELEVATION_SOURCE.sampleTerrainGradientAtResolution(worldX, worldZ, this.settings.worldScale(), lodResolutionMeters);
      }
      TerrainSlopePolicy.Gradient gradient = ELEVATION_SOURCE.sampleTerrainGradientLocalOnly(worldX, worldZ, this.settings.worldScale());
      if (Double.isFinite(gradient.slopeDegrees())) {
         return gradient;
      }

      TerrainSlopePolicy.Gradient fallback = this.sampleGeneratedTerrainGradient(worldX, worldZ);
      TerrainSlopePolicy.Gradient hydrated = ELEVATION_SOURCE.sampleTerrainGradientLocalOnly(worldX, worldZ, this.settings.worldScale());
      return Double.isFinite(hydrated.slopeDegrees()) ? hydrated : fallback;
   }

   /**
    * The slope the snow bands read here: the DEM slope, counted gentler on faces turned to the pole
    * and steeper on faces turned to the equator. A distant LOD column draws it from the slopes real
    * chunks show in such a cell, since its own coarse data smooths the steep faces away.
    */
   public double sampleSnowSlopeDegrees(int worldX, int worldZ) {
      double latitude = EarthProjection.blockZToLat(worldZ, this.settings.worldScale());
      LodSnowSlope.Level level = this.lodSnowSlopeLevel.get();
      if (level == null || level.exact()) {
         return SnowSlopePolicy.effectiveSlopeDegrees(this.sampleDemGradient(worldX, worldZ), latitude);
      }

      TerrainSlopePolicy.Neighbourhood neighbourhood = ELEVATION_SOURCE.sampleTerrainNeighbourhoodAtResolution(
         worldX, worldZ, this.settings.worldScale(), this.lodSlopeResolutionMeters.get()
      );
      TerrainSlopePolicy.Gradient cell = neighbourhood.gradient();
      double slopeDegrees = LodSnowSlope.drawSlopeDegrees(
         level,
         cell.slopeDegrees(),
         neighbourhood.relief(),
         cell.centerElevation() - this.mountainZoneCell(worldX, worldZ).treelineMeters(),
         worldX,
         worldZ
      );
      return SnowSlopePolicy.effectiveSlopeDegrees(slopeDegrees, cell.polewardFacing(latitude), latitude);
   }

   private TerrainSlopePolicy.Gradient sampleGeneratedTerrainGradient(int worldX, int worldZ) {
      double heightScale = this.settings.effectiveTerrestrialHeightScale();
      if (!(heightScale > 0.0) || !(this.settings.effectiveVerticalWorldScale() > 0.0)) {
         return TerrainSlopePolicy.Gradient.MISSING;
      }

      int step = TerrainSlopePolicy.SAMPLE_RADIUS_BLOCKS;
      if (this.settings.experimentalIncreaseHeight()) {
         return TerrainSlopePolicy.localGradient(
            this.sampleSurfaceHeight(worldX, worldZ),
            this.sampleSurfaceHeight(worldX + step, worldZ),
            this.sampleSurfaceHeight(worldX - step, worldZ),
            this.sampleSurfaceHeight(worldX, worldZ - step),
            this.sampleSurfaceHeight(worldX, worldZ + step),
            step,
            step
         );
      }

      double center = this.generatedHeightToElevationMeters(this.sampleSurfaceHeight(worldX, worldZ), worldZ, heightScale);
      double east = this.generatedHeightToElevationMeters(this.sampleSurfaceHeight(worldX + step, worldZ), worldZ, heightScale);
      double west = this.generatedHeightToElevationMeters(this.sampleSurfaceHeight(worldX - step, worldZ), worldZ, heightScale);
      double north = this.generatedHeightToElevationMeters(this.sampleSurfaceHeight(worldX, worldZ - step), worldZ - step, heightScale);
      double south = this.generatedHeightToElevationMeters(this.sampleSurfaceHeight(worldX, worldZ + step), worldZ + step, heightScale);
      return TerrainSlopePolicy.localGradient(
         center,
         east,
         west,
         north,
         south,
         step * EarthProjection.groundMetersPerBlockX(worldZ, this.settings.worldScale()),
         step * EarthProjection.groundMetersPerBlockZ(worldZ, this.settings.worldScale())
      );
   }

   private double generatedHeightToElevationMeters(int height, int blockZ, double heightScale) {
      double projectionScale = this.settings.automaticHeightScaling()
         ? EarthProjection.heightScaleCorrection(blockZ, this.settings.worldScale())
         : 1.0;
      return (height - this.settings.effectiveHeightOffset())
         * this.settings.effectiveVerticalWorldScale()
         / (heightScale * projectionScale);
   }

   /**
    * Height above sea level on the mountain rules' anchor scale, so the highland
    * and alpine zones sit at real elevations around the local treeline at any world scale.
    */
   private int mountainZoneHeight(int surface, int worldX, int worldZ) {
      int heightAboveSea = surface - this.seaLevel;
      if (heightAboveSea <= 0) {
         return heightAboveSea;
      }

      EarthChunkGenerator.MountainZoneRow row = this.mountainZoneRow(worldZ);
      // Every rule reads the same zone for columns this low under any local treeline, so they skip the lookup.
      return MountainElevationZones.zoneHeight(
         heightAboveSea, heightAboveSea < row.uniformBelowBlocks() ? row.curveBand() : this.mountainZoneBand(worldX, worldZ)
      );
   }

   private EarthChunkGenerator.MountainZoneRow mountainZoneRow(int worldZ) {
      int row = worldZ >> MOUNTAIN_ZONE_ROW_SHIFT;
      int slot = row & (MOUNTAIN_ZONE_ROW_CACHE_SIZE - 1);
      EarthChunkGenerator.MountainZoneRow cached = this.mountainZoneRows[slot];
      if (cached != null && cached.row() == row) {
         return cached;
      }

      double latitude = EarthProjection.blockZToLat((row + 0.5) * (1 << MOUNTAIN_ZONE_ROW_SHIFT), this.settings.worldScale());
      EarthChunkGenerator.MountainZoneRow computed = new EarthChunkGenerator.MountainZoneRow(
         row,
         MountainElevationZones.band(
            latitude,
            this.settings.effectiveVerticalWorldScale(),
            this.settings.effectiveTerrestrialHeightScale(),
            this.settings.effectiveOceanicHeightScale(),
            this.settings.experimentalIncreaseHeight(),
            this.settings.automaticHeightScaling()
         ),
         MountainElevationZones.uniformZoneCeilingBlocks(
            MountainElevationZones.treelineMeters(latitude) - LocalTreeline.MAX_DEVIATION_METERS,
            latitude,
            this.settings.effectiveVerticalWorldScale(),
            this.settings.effectiveTerrestrialHeightScale(),
            this.settings.effectiveOceanicHeightScale(),
            this.settings.experimentalIncreaseHeight(),
            this.settings.automaticHeightScaling()
         )
      );
      this.mountainZoneRows[slot] = computed;
      return computed;
   }

   private MountainElevationZones.Band mountainZoneBand(int worldX, int worldZ) {
      return this.mountainZoneCell(worldX, worldZ).band();
   }

   private EarthChunkGenerator.MountainZoneCell mountainZoneCell(int worldX, int worldZ) {
      int cellX = worldX >> MOUNTAIN_ZONE_CELL_SHIFT;
      int cellZ = worldZ >> MOUNTAIN_ZONE_CELL_SHIFT;
      long key = (long)cellX << 32 | (long)cellZ & 0xFFFFFFFFL;
      int slot = (int)(key * -7046029254386353131L >>> (64 - MOUNTAIN_ZONE_CACHE_BITS));
      EarthChunkGenerator.MountainZoneCell cached = this.mountainZoneCells[slot];
      if (cached != null && cached.key() == key) {
         return cached;
      }

      double cellBlocks = 1 << MOUNTAIN_ZONE_CELL_SHIFT;
      double latitude = EarthProjection.blockZToLat((cellZ + 0.5) * cellBlocks, this.settings.worldScale());
      double longitude = EarthProjection.blockXToLongitude((cellX + 0.5) * cellBlocks, this.settings.worldScale());
      double treelineMeters = this.localTreeline.treelineMeters(latitude, longitude);
      if (!Double.isFinite(treelineMeters)) {
         treelineMeters = MountainElevationZones.treelineMeters(latitude);
      }
      MountainElevationZones.Band band = MountainElevationZones.bandForTreeline(
         treelineMeters,
         latitude,
         this.settings.effectiveVerticalWorldScale(),
         this.settings.effectiveTerrestrialHeightScale(),
         this.settings.effectiveOceanicHeightScale(),
         this.settings.experimentalIncreaseHeight(),
         this.settings.automaticHeightScaling()
      );
      EarthChunkGenerator.MountainZoneCell computed = new EarthChunkGenerator.MountainZoneCell(key, band, treelineMeters);
      this.mountainZoneCells[slot] = computed;
      return computed;
   }

   private record MountainZoneCell(long key, MountainElevationZones.Band band, double treelineMeters) {
   }

   private record MountainZoneRow(int row, MountainElevationZones.Band curveBand, double uniformBelowBlocks) {
   }

   public boolean shouldPlaceSnowAt(int worldX, int worldZ) {
      return SnowSlopePolicy.shouldCover(worldX, worldZ, this.sampleSnowSlopeDegrees(worldX, worldZ));
   }

   private boolean shouldRetainPersistentSnowAt(
      int worldX,
      int worldZ,
      int surfaceCoverClass,
      boolean snowLikeTerrain
   ) {
      if (snowLikeTerrain) {
         return this.shouldPlaceSnowAt(worldX, worldZ);
      }

      boolean localSnowSource = surfaceCoverClass == MountainSurfaceRules.ESA_SNOW_ICE;
      if (!localSnowSource) {
         return false;
      }

      return SnowTransitionPolicy.shouldCover(
         worldX,
         worldZ,
         this.sampleSnowSlopeDegrees(worldX, worldZ),
         localSnowSource,
         this.snowTransitionSourceSampler,
         this.worldSeed
      );
   }

   public boolean shouldPlaceDeepslateAt(int worldX, int worldZ) {
      return DeepslateSlopePolicy.shouldCover(worldX, worldZ, this.sampleDemSlopeDegrees(worldX, worldZ));
   }

   private EarthChunkGenerator.SurfacePalette applyDemDeepslateSlopePaletteOverride(
      EarthChunkGenerator.SurfacePalette palette,
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      boolean underwater,
      int localReliefBlocks
   ) {
      if (palette == null
         || underwater
         || biome.is(BiomeTags.IS_BADLANDS)
         || palette.top().is(Blocks.SNOW_BLOCK)
         || palette.landformSurface()
         || !this.shouldPlaceDeepslateAt(worldX, worldZ)) {
         return palette;
      }

      return EarthChunkGenerator.SurfacePalette.deepslate(
         DeepslateSlopePolicy.surfaceDepthForRelief(palette.depth(), localReliefBlocks)
      );
   }

   private MountainSurfaceRules.ApproximateSurface classifyMountainSurface(
      int surfaceCoverClass,
      int heightAboveSea,
      int slopeDiff,
      int convexity,
      boolean snowLikeTerrain,
      float vegetationTransitionWeight,
      int worldX,
      int worldZ,
      OsmQueryMode mountainOsmQueryMode
   ) {
      boolean rawSnowSource = MountainSurfaceRules.hasSnowSource(surfaceCoverClass, snowLikeTerrain);
      boolean retainSnow = this.shouldRetainPersistentSnowAt(
         worldX, worldZ, surfaceCoverClass, snowLikeTerrain
      );
      MountainSurfaceRules.ApproximateSurface classified = MountainSurfaceRules.classifyApproximateSurface(
         surfaceCoverClass,
         surfaceCoverClass,
         heightAboveSea,
         slopeDiff,
         convexity,
         snowLikeTerrain,
         vegetationTransitionWeight,
         worldX,
         worldZ
      );
      if (!rawSnowSource && !retainSnow) {
         return classified;
      }

      MountainSurfaceRules.ApproximatePalette snowPalette = retainSnow
         ? MountainSurfaceRules.ApproximatePalette.SNOW
         : MountainSurfaceRules.ApproximatePalette.STONE;
      if (classified.palette() == snowPalette) {
         return classified;
      }

      MountainSurfaceRules.MountainForm form = retainSnow
         ? MountainSurfaceRules.snowForm(slopeDiff, convexity)
         : MountainSurfaceRules.landform(surfaceCoverClass, heightAboveSea, slopeDiff, convexity, worldX, worldZ);
      return new MountainSurfaceRules.ApproximateSurface(classified.surfaceCoverClass(), snowPalette, form);
   }

   private boolean hasPersistentSnowSourceAt(int sampleX, int sampleZ) {
      EarthChunkGenerator.LodMountainTransitionCache cache = this.lodMountainTransitionCache.get();
      return cache != null
         ? cache.hasSnowSource(sampleX, sampleZ)
         : this.hasPersistentSnowSourceAtUncached(sampleX, sampleZ, this.settings.worldScale());
   }

   private boolean hasPersistentSnowSourceAtUncached(int sampleX, int sampleZ, double previewResolutionMeters) {
      int surfaceCoverClass = this.mountainSamplingCache(previewResolutionMeters).surfaceCoverClass(sampleX, sampleZ);
      return MountainSurfaceRules.hasSnowSource(surfaceCoverClass, this.isAntarcticSnowTerrain(surfaceCoverClass, sampleZ));
   }

   private EarthChunkGenerator.MountainSamplingCache mountainSamplingCache(double previewResolutionMeters) {
      EarthChunkGenerator.MountainSamplingCache cache = this.mountainSamplingCache.get();
      cache.prepare(previewResolutionMeters);
      return cache;
   }

   public EarthChunkGenerator.LodSurface resolveLodSurface(Holder<Biome> biome, int worldX, int worldZ, int surface, boolean underwater) {
      int rawCoverClass = this.sampleCoverClass(worldX, worldZ);
      int visualCoverClass = this.sampleVisualCoverClass(worldX, worldZ, rawCoverClass);
      return this.resolveLodSurface(biome, worldX, worldZ, surface, underwater, rawCoverClass, visualCoverClass);
   }


   public EarthChunkGenerator.LodSurface resolveLodSurface(Holder<Biome> biome, int worldX, int worldZ, int surface, boolean underwater, int coverClass) {
      return this.resolveLodSurface(biome, worldX, worldZ, surface, underwater, coverClass, coverClass);
   }

   public EarthChunkGenerator.LodSurface resolveLodSurface(
      Holder<Biome> biome, int worldX, int worldZ, int surface, boolean underwater, int rawCoverClass, int visualCoverClass
   ) {
      int slopeDiff = this.sampleSlopeDiff(worldX, worldZ, surface);
      int convexity = this.sampleConvexity(worldX, worldZ, surface);
      return this.resolveLodSurface(biome, worldX, worldZ, surface, underwater, rawCoverClass, visualCoverClass, slopeDiff, convexity);
   }

   public EarthChunkGenerator.LodSurface resolveLodSurface(
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int rawCoverClass,
      int visualCoverClass,
      EarthChunkGenerator.LodSurfaceProfiler profiler
   ) {
      int slopeDiff = this.sampleSlopeDiff(worldX, worldZ, surface);
      int convexity = this.sampleConvexity(worldX, worldZ, surface);
      return this.resolveLodSurface(biome, worldX, worldZ, surface, underwater, rawCoverClass, visualCoverClass, slopeDiff, convexity, profiler);
   }

   public EarthChunkGenerator.LodSurface resolveLodSurface(
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int rawCoverClass,
      int visualCoverClass,
      EarthChunkGenerator.LodShorelineCache shorelineCache
   ) {
      int slopeDiff = this.sampleSlopeDiff(worldX, worldZ, surface);
      int convexity = this.sampleConvexity(worldX, worldZ, surface);
      return this.resolveLodSurface(biome, worldX, worldZ, surface, underwater, rawCoverClass, visualCoverClass, slopeDiff, convexity, null, shorelineCache);
   }

   public EarthChunkGenerator.LodSurface resolveLodSurface(
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int rawCoverClass,
      int visualCoverClass,
      EarthChunkGenerator.LodShorelineCache shorelineCache,
      OsmQueryMode mountainOsmQueryMode
   ) {
      int slopeDiff = this.sampleSlopeDiff(worldX, worldZ, surface);
      int convexity = this.sampleConvexity(worldX, worldZ, surface);
      return this.resolveLodSurface(
         biome, worldX, worldZ, surface, underwater, rawCoverClass, visualCoverClass, slopeDiff, convexity, null, shorelineCache, mountainOsmQueryMode
      );
   }

   public EarthChunkGenerator.LodSurface resolveLodSurface(
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int rawCoverClass,
      int visualCoverClass,
      EarthChunkGenerator.LodSurfaceProfiler profiler,
      EarthChunkGenerator.LodShorelineCache shorelineCache
   ) {
      int slopeDiff = this.sampleSlopeDiff(worldX, worldZ, surface);
      int convexity = this.sampleConvexity(worldX, worldZ, surface);
      return this.resolveLodSurface(biome, worldX, worldZ, surface, underwater, rawCoverClass, visualCoverClass, slopeDiff, convexity, profiler, shorelineCache);
   }

   public EarthChunkGenerator.LodSurface resolveLodSurface(
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int rawCoverClass,
      int visualCoverClass,
      EarthChunkGenerator.LodSurfaceProfiler profiler,
      EarthChunkGenerator.LodShorelineCache shorelineCache,
      OsmQueryMode mountainOsmQueryMode
   ) {
      int slopeDiff = this.sampleSlopeDiff(worldX, worldZ, surface);
      int convexity = this.sampleConvexity(worldX, worldZ, surface);
      return this.resolveLodSurface(
         biome, worldX, worldZ, surface, underwater, rawCoverClass, visualCoverClass, slopeDiff, convexity, profiler, shorelineCache, mountainOsmQueryMode
      );
   }

   public EarthChunkGenerator.LodSurface resolveLodSurface(
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int rawCoverClass,
      int visualCoverClass,
      int slopeDiff,
      int convexity
   ) {
      return this.resolveLodSurface(
         biome,
         worldX,
         worldZ,
         surface,
         underwater,
         rawCoverClass,
         visualCoverClass,
         slopeDiff,
         convexity,
         (EarthChunkGenerator.LodSurfaceProfiler)null,
         null
      );
   }

   public EarthChunkGenerator.LodSurface resolveLodSurface(
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int rawCoverClass,
      int visualCoverClass,
      int slopeDiff,
      int convexity,
      EarthChunkGenerator.LodShorelineCache shorelineCache
   ) {
      return this.resolveLodSurface(
         biome, worldX, worldZ, surface, underwater, rawCoverClass, visualCoverClass, slopeDiff, convexity, null, shorelineCache
      );
   }

   public EarthChunkGenerator.LodSurface resolveLodSurface(
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int rawCoverClass,
      int visualCoverClass,
      int slopeDiff,
      int convexity,
      EarthChunkGenerator.LodShorelineCache shorelineCache,
      OsmQueryMode mountainOsmQueryMode
   ) {
      return this.resolveLodSurface(
         biome, worldX, worldZ, surface, underwater, rawCoverClass, visualCoverClass, slopeDiff, convexity, null, shorelineCache, mountainOsmQueryMode
      );
   }

   public EarthChunkGenerator.LodSurface resolveLodSurface(
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int rawCoverClass,
      int visualCoverClass,
      int slopeDiff,
      int convexity,
      EarthChunkGenerator.LodSurfaceProfiler profiler
   ) {
      return this.resolveLodSurface(
         biome, worldX, worldZ, surface, underwater, rawCoverClass, visualCoverClass, slopeDiff, convexity, profiler, null
      );
   }

   public EarthChunkGenerator.LodSurface resolveLodSurface(
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int rawCoverClass,
      int visualCoverClass,
      int slopeDiff,
      int convexity,
      EarthChunkGenerator.LodSurfaceProfiler profiler,
      EarthChunkGenerator.LodShorelineCache shorelineCache
   ) {
      return this.resolveLodSurface(
         biome,
         worldX,
         worldZ,
         surface,
         underwater,
         rawCoverClass,
         visualCoverClass,
         slopeDiff,
         convexity,
         profiler,
         shorelineCache,
         this.resolveFullChunkOsmQueryMode()
      );
   }

   public EarthChunkGenerator.LodSurface resolveLodSurface(
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int rawCoverClass,
      int visualCoverClass,
      int slopeDiff,
      int convexity,
      EarthChunkGenerator.LodSurfaceProfiler profiler,
      EarthChunkGenerator.LodShorelineCache shorelineCache,
      OsmQueryMode mountainOsmQueryMode
   ) {
      long phaseStart = beginLodSurfaceProfiling(profiler);
      int effectiveCoverClass = this.resolveEffectiveCoverClassForTerrain(rawCoverClass);
      endLodSurfaceProfiling(profiler, "generator.effectiveCover", phaseStart);
      phaseStart = beginLodSurfaceProfiling(profiler);
      int surfaceCoverClass = this.resolveSurfaceCoverClassForTerrain(effectiveCoverClass, visualCoverClass);
      endLodSurfaceProfiling(profiler, "generator.surfaceCoverClass", phaseStart);
      EarthChunkGenerator.SurfacePalette palette = this.selectSurfacePalette(
         biome, worldX, worldZ, surface, underwater, slopeDiff, convexity, surfaceCoverClass, profiler, shorelineCache, mountainOsmQueryMode
      );
      if (palette == null) {
         profilerAddPhase(profiler, "generator.result", 0L);
         return new EarthChunkGenerator.LodSurface(STONE_STATE, STONE_STATE);
      } else {
         phaseStart = beginLodSurfaceProfiling(profiler);
         BlockState top = underwater ? palette.underwaterTop() : palette.top();
         BlockState filler = resolveLodSurfaceFiller(palette, top, underwater);
         EarthChunkGenerator.LodSurface lodSurface = new EarthChunkGenerator.LodSurface(top, filler);
         endLodSurfaceProfiling(profiler, "generator.result", phaseStart);
         return lodSurface;
      }
   }

   public EarthChunkGenerator.LodSurface resolveUltraFastLodSurface(
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int rawCoverClass,
      int visualCoverClass,
      int slopeDiff,
      int convexity,
      boolean snowLikeTerrain
   ) {
      return this.resolveUltraFastLodSurface(
         biome, worldX, worldZ, surface, underwater, rawCoverClass, visualCoverClass, slopeDiff, convexity, snowLikeTerrain, this.resolveFullChunkOsmQueryMode()
      );
   }

   public EarthChunkGenerator.LodSurface resolveUltraFastLodSurface(
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int rawCoverClass,
      int visualCoverClass,
      int slopeDiff,
      int convexity,
      boolean snowLikeTerrain,
      OsmQueryMode mountainOsmQueryMode
   ) {
      return this.resolveUltraFastLodSurface(
         biome,
         worldX,
         worldZ,
         surface,
         underwater,
         rawCoverClass,
         visualCoverClass,
         slopeDiff,
         convexity,
         snowLikeTerrain,
         mountainOsmQueryMode,
         true
      );
   }

   public EarthChunkGenerator.LodSurface resolveUltraFastLodSurface(
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int rawCoverClass,
      int visualCoverClass,
      int slopeDiff,
      int convexity,
      boolean snowLikeTerrain,
      OsmQueryMode mountainOsmQueryMode,
      boolean allowOsmSand
   ) {
      int effectiveCoverClass = this.resolveEffectiveCoverClassForTerrain(rawCoverClass);
      int surfaceCoverClass = this.resolveSurfaceCoverClassForTerrain(effectiveCoverClass, visualCoverClass);
      EarthChunkGenerator.SurfacePalette palette;
      palette = this.selectBaseSurfacePalette(biome, worldX, worldZ, surface, surfaceCoverClass);
      if (palette == null) {
         return new EarthChunkGenerator.LodSurface(STONE_STATE, STONE_STATE);
      }

      palette = this.applySlopeSurfaceOverride(
         palette, underwater, slopeDiff, convexity, surfaceCoverClass, biome, worldX, worldZ, surface, null, snowLikeTerrain, mountainOsmQueryMode
      );

      if (allowOsmSand) {
         palette = this.applyOvertureSandPaletteOverride(palette, worldX, worldZ, underwater, mountainOsmQueryMode);
      }
      palette = this.applyDemDeepslateSlopePaletteOverride(palette, biome, worldX, worldZ, underwater, slopeDiff);
      palette = applyBadlandsCliffPalette(palette, biome, worldX, worldZ, surface, underwater, slopeDiff);
      BlockState top = underwater ? palette.underwaterTop() : palette.top();
      return new EarthChunkGenerator.LodSurface(top, resolveLodSurfaceFiller(palette, top, underwater));
   }

   private static BlockState resolveLodSurfaceFiller(EarthChunkGenerator.SurfacePalette palette, BlockState top, boolean underwater) {
      BlockState filler = palette.filler();
      if (underwater) {
         return filler;
      } else if (top.is(Blocks.SNOW_BLOCK)) {
         return resolveLodSnowFillerBlock(filler);
      }

      // Turf works like snow: the dirt under a one-block grass cap is what distant slopes show.
      return top.is(Blocks.GRASS_BLOCK) ? top : filler;
   }

   /**
    * Filler for a dry snow-capped LOD column. Distant slopes show a column's sides, which read as stone
    * under a one-block cap, so the whole column is snow. Deepslate stays, as steep slopes expose it.
    */
   public static BlockState resolveLodSnowFillerBlock(BlockState filler) {
      return filler.is(Blocks.DEEPSLATE) ? filler : SNOW_BLOCK_STATE;
   }

   public EarthChunkGenerator.LodShorelineCache buildLodShorelineCache(int minWorldX, int maxWorldX, int minWorldZ, int maxWorldZ) {
      return this.buildLodShorelineCache(minWorldX, maxWorldX, minWorldZ, maxWorldZ, this.settings.worldScale());
   }

   public EarthChunkGenerator.LodShorelineCache buildLodShorelineCache(
      int minWorldX, int maxWorldX, int minWorldZ, int maxWorldZ, double previewResolutionMeters
   ) {
      int minX = minWorldX - OCEAN_SHORE_MAX_DISTANCE;
      int maxX = maxWorldX + OCEAN_SHORE_MAX_DISTANCE;
      int minZ = minWorldZ - OCEAN_SHORE_MAX_DISTANCE;
      int maxZ = maxWorldZ + OCEAN_SHORE_MAX_DISTANCE;
      long width = (long)maxX - (long)minX + 1L;
      long height = (long)maxZ - (long)minZ + 1L;
      long area = width * height;
      return area > (long)LOD_SHORELINE_DENSE_CACHE_MAX_AREA
         ? new EarthChunkGenerator.SparseLodShorelineCache(previewResolutionMeters)
         : this.buildDenseLodShorelineCache(minX, maxX, minZ, maxZ, (int)width, (int)height, previewResolutionMeters);
   }

   /**
    * The shoreline cache of a LOD tile whose cells span {@code cellSize} blocks. Its water lookups are made at
    * the cell centres, where the tile's columns are sampled, and every block answers with its cell: a 4-block
    * tile resolves 4,624 water columns instead of 73,984. Shore bands are 4 to 8 blocks wide, one or two cells
    * there, so the tile's beaches and banks stay where they were.
    */
   public EarthChunkGenerator.LodShorelineCache buildLodShorelineCache(
      int minWorldX, int maxWorldX, int minWorldZ, int maxWorldZ, int cellSize, double previewResolutionMeters
   ) {
      if (cellSize <= 1) {
         return this.buildLodShorelineCache(minWorldX, maxWorldX, minWorldZ, maxWorldZ, previewResolutionMeters);
      }

      int marginCells = (OCEAN_SHORE_MAX_DISTANCE + cellSize - 1) / cellSize;
      int originX = minWorldX - marginCells * cellSize;
      int originZ = minWorldZ - marginCells * cellSize;
      int columns = (maxWorldX - minWorldX) / cellSize + 1 + 2 * marginCells;
      int rows = (maxWorldZ - minWorldZ) / cellSize + 1 + 2 * marginCells;
      WaterSurfaceResolver.WaterInfo[] waterInfos = new WaterSurfaceResolver.WaterInfo[columns * rows];
      boolean hasWater = false;
      int index = 0;

      for (int row = 0; row < rows; row++) {
         int z = originZ + row * cellSize;

         for (int column = 0; column < columns; column++) {
            WaterSurfaceResolver.WaterInfo info = this.resolveFastWaterInfoForShoreline(originX + column * cellSize, z, previewResolutionMeters);
            waterInfos[index++] = info;
            hasWater |= info.isWater();
         }
      }

      return new EarthChunkGenerator.CellLodShorelineCache(
         originX, originZ, cellSize, columns, rows, waterInfos, hasWater, previewResolutionMeters
      );
   }

   public EarthChunkGenerator.LodSharedTerrainCache buildLodSharedTerrainCache(int minWorldX, int maxWorldX, int minWorldZ, int maxWorldZ) {
      return this.buildLodSharedTerrainCache(minWorldX, maxWorldX, minWorldZ, maxWorldZ, this.settings.worldScale());
   }

   public EarthChunkGenerator.LodSharedTerrainCache buildLodSharedTerrainCache(
      int minWorldX, int maxWorldX, int minWorldZ, int maxWorldZ, double previewResolutionMeters
   ) {
      int coverMinX = minWorldX - SnowTransitionPolicy.MAX_EDGE_DISPLACEMENT_BLOCKS;
      int coverMaxX = maxWorldX + SnowTransitionPolicy.MAX_EDGE_DISPLACEMENT_BLOCKS;
      int coverMinZ = minWorldZ - SnowTransitionPolicy.MAX_EDGE_DISPLACEMENT_BLOCKS;
      int coverMaxZ = maxWorldZ + SnowTransitionPolicy.MAX_EDGE_DISPLACEMENT_BLOCKS;
      long coverWidth = (long)coverMaxX - (long)coverMinX + 1L;
      long coverHeight = (long)coverMaxZ - (long)coverMinZ + 1L;
      long coverArea = coverWidth * coverHeight;
      int shoreMinX = minWorldX - OCEAN_SHORE_MAX_DISTANCE;
      int shoreMaxX = maxWorldX + OCEAN_SHORE_MAX_DISTANCE;
      int shoreMinZ = minWorldZ - OCEAN_SHORE_MAX_DISTANCE;
      int shoreMaxZ = maxWorldZ + OCEAN_SHORE_MAX_DISTANCE;
      long shoreWidth = (long)shoreMaxX - (long)shoreMinX + 1L;
      long shoreHeight = (long)shoreMaxZ - (long)shoreMinZ + 1L;
      long shoreArea = shoreWidth * shoreHeight;
      return coverArea <= (long)LOD_MOUNTAIN_TRANSITION_DENSE_CACHE_MAX_AREA && shoreArea <= (long)LOD_SHORELINE_DENSE_CACHE_MAX_AREA
         ? this.buildDenseLodSharedTerrainCache(
            coverMinX,
            coverMaxX,
            coverMinZ,
            coverMaxZ,
            (int)coverWidth,
            (int)coverHeight,
            shoreMinX,
            shoreMaxX,
            shoreMinZ,
            shoreMaxZ,
            (int)shoreWidth,
            (int)shoreHeight,
            previewResolutionMeters
         )
         : null;
   }

   public EarthChunkGenerator.LodMountainTransitionCache buildLodMountainTransitionCache(
      int minWorldX, int maxWorldX, int minWorldZ, int maxWorldZ
   ) {
      return this.buildLodMountainTransitionCache(minWorldX, maxWorldX, minWorldZ, maxWorldZ, this.settings.worldScale());
   }

   public EarthChunkGenerator.LodMountainTransitionCache buildLodMountainTransitionCache(
      int minWorldX, int maxWorldX, int minWorldZ, int maxWorldZ, double previewResolutionMeters
   ) {
      return new EarthChunkGenerator.LazyLodMountainTransitionCache(previewResolutionMeters);
   }

   public void setLodMountainTransitionCache(EarthChunkGenerator.LodMountainTransitionCache cache) {
      if (cache == null) {
         this.lodMountainTransitionCache.remove();
      } else {
         this.lodMountainTransitionCache.set(cache);
      }
   }

   public void clearLodMountainTransitionCache() {
      this.lodMountainTransitionCache.remove();
   }

   /** Surface slope rules on this thread sample the DEM at a distant LOD's resolution until cleared. */
   public void setLodSlopeResolution(double resolutionMeters) {
      if (Double.isFinite(resolutionMeters) && resolutionMeters > this.settings.worldScale()) {
         this.lodSlopeResolutionMeters.set(resolutionMeters);
         this.lodSnowSlopeLevel.set(LodSnowSlope.level(this.settings.worldScale(), resolutionMeters));
      } else {
         this.clearLodSlopeResolution();
      }
   }

   public void clearLodSlopeResolution() {
      this.lodSlopeResolutionMeters.remove();
      this.lodSnowSlopeLevel.remove();
   }

   /** Overture sand lookups on this thread read tiles sized to a LOD column of this resolution until cleared. */
   public void setLodSandResolution(double resolutionMeters) {
      this.lodSandResolutionMeters.set(resolutionMeters);
   }

   public void clearLodSandResolution() {
      this.lodSandResolutionMeters.remove();
   }

   public void setLodShorelineOverrideSuppressed(boolean suppressed) {
      if (suppressed) {
         this.lodShorelineOverrideSuppressed.set(Boolean.TRUE);
      } else {
         this.lodShorelineOverrideSuppressed.remove();
      }
   }

   public void clearLodShorelineOverrideSuppressed() {
      this.lodShorelineOverrideSuppressed.remove();
   }

   public void processDeferredChunkDetailTick(ServerLevel level) {
      this.reapStalePreparedChunkState(level.getGameTime());
      // Not only in deferred mode: chunks built while coastline or elevation data was unavailable are refined too.
      this.terrainRefinementManager.applyReady(level, this, TERRAIN_REFINEMENT_APPLY_BUDGET_PER_TICK);

      if (this.usesDeferredChunkDetails()) {
         this.chunkDetailManager.prefetchAroundPlayers(level, this, CHUNK_DETAIL_PREFETCH_RADIUS);
         if (this.hasDeferredApplyWork()) {
            this.chunkDetailManager.applyReady(level, this, CHUNK_DETAIL_APPLY_BUDGET_PER_TICK);
         }
      }
   }

   private void applyReadyDeferredChunkDetail(WorldGenLevel level, ChunkAccess chunk, List<BoundingBox> campPieces) {
      if (this.hasDeferredApplyWork()) {
         EarthChunkGenerator.PreparedChunkDetail detail = this.chunkDetailManager.claimReady(chunk.getPos());
         if (detail != null) {
            this.applyPreparedChunkDetail(level, chunk, detail, campPieces);
         }
      }
   }

   private EarthChunkGenerator.PreparedChunkDetail prepareDeferredChunkDetail(EarthChunkGenerator.ChunkGenerationContext context) {
      EarthChunkGenerator.PreparedChunkDetail detail = this.buildPreparedChunkDetail(
         context, this.shouldDeferBuildingDetails(), this.shouldDeferBuildingDetails(), this.shouldDeferRoadDetails(), this.shouldDeferTrees(), OsmQueryMode.NON_BLOCKING
      );
      if (this.shouldDeferDetailedWater()) {
         this.waterResolver.prefetchRegionsForChunk(
            MinecraftVersionCompat.chunkX(context.pos()),
            MinecraftVersionCompat.chunkZ(context.pos()),
            Math.max(1, CHUNK_DETAIL_PREFETCH_RADIUS)
         );
      }

      return detail;
   }

   private EarthChunkGenerator.PreparedChunkDetail buildPreparedChunkDetail(
      EarthChunkGenerator.ChunkGenerationContext context,
      boolean prepareBuildings,
      boolean placeBuildings,
      boolean prepareRoads,
      boolean prepareTrees,
      OsmQueryMode queryMode
   ) {
      EarthChunkGenerator.PreparedChunkBuildings preparedBuildings = prepareBuildings
         ? this.buildChunkBuildings(
            context.pos(),
            context.terrainSurfaces(),
            context.minY(),
            context.maxY(),
            (feature, baseY) -> this.sampleBuildingBiome(feature, baseY, context),
            queryMode
         )
         : context.preparedBuildings();
      boolean shouldPlaceBuildings = placeBuildings && preparedBuildings != null && !preparedBuildings.isEmpty();
      EarthChunkGenerator.OsmRoadQueryResult roadQuery = prepareRoads ? this.fetchDeferredRoadQuery(context.pos(), queryMode) : null;
      List<EarthChunkGenerator.PreparedTreePlacement> treePlacements = List.of();
      if (prepareTrees) {
         long treePrepStartNs = beginFullChunkProfiling();
         treePlacements = this.prepareDeferredTreePlacements(context, preparedBuildings);
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_DETAIL_TREE_PREP, treePrepStartNs);
      }

      return new EarthChunkGenerator.PreparedChunkDetail(context, preparedBuildings, shouldPlaceBuildings, roadQuery, treePlacements);
   }

   private EarthChunkGenerator.OsmRoadQueryResult fetchDeferredRoadQuery(ChunkPos pos, OsmQueryMode queryMode) {
      int chunkMinX = pos.getMinBlockX();
      int chunkMinZ = pos.getMinBlockZ();
      int chunkMaxX = chunkMinX + CHUNK_MASK;
      int chunkMaxZ = chunkMinZ + CHUNK_MASK;
      return this.fetchOsmRoadsForAreaDetailed(chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ, OSM_ROAD_QUERY_MARGIN, queryMode);
   }

   private EarthChunkGenerator.PreparedTerrainRefinement buildPreparedTerrainRefinement(EarthChunkGenerator.TerrainShellBuildResult shell) {
      ChunkPos pos = shell.pos();
      int unavailableElevationReads = TellusElevationSource.unavailableTileReads();
      int step = 4;
      int gridSize = 16 + step * 2;
      EarthChunkGenerator.HeightGridBuildResult exactHeightGrid = this.buildHeightGrid(pos, step, gridSize, FAST_FULL_CHUNK, NON_BLOCKING_TERRAIN_INPUTS);
      int[] heightGrid = exactHeightGrid.heightGrid();
      int[] rawCoverClasses = new int[CHUNK_AREA];
      int[] terrainSurfaces = new int[CHUNK_AREA];
      int[] waterSurfaces = new int[CHUNK_AREA];
      boolean[] waterFlags = new boolean[CHUNK_AREA];
      int[] coverClasses = new int[CHUNK_AREA];
      int[] visualCoverClasses = new int[CHUNK_AREA];
      int[] surfaceCoverClasses = new int[CHUNK_AREA];
      boolean[] oceanFlags = new boolean[CHUNK_AREA];
      int[] slopeDiffs = new int[CHUNK_AREA];
      int[] convexities = new int[CHUNK_AREA];
      int chunkMinY = shell.minY();
      int chunkMaxY = shell.maxY() + 1;

      copyChunkTerrainSurfaces(heightGrid, gridSize, step, terrainSurfaces);

      this.fillBaseTerrainRawCoverClasses(pos, rawCoverClasses);
      WaterSurfaceResolver.WaterChunkData exactWaterData = this.buildExactWaterChunkData(pos, terrainSurfaces, rawCoverClasses);
      this.fillExactChunkTerrainColumns(
         pos,
         chunkMinY,
         chunkMaxY,
         step,
         gridSize,
         heightGrid,
         exactWaterData,
         rawCoverClasses,
         terrainSurfaces,
         waterSurfaces,
         waterFlags,
         coverClasses,
         visualCoverClasses,
         surfaceCoverClasses,
         oceanFlags,
         false
      );

      // Same steps and order as fillTellusSurface, so a refined chunk matches one generated with all data.
      this.applyShorelineBankRamp(pos, terrainSurfaces, waterSurfaces, waterFlags, exactWaterData);
      copyChunkTerrainSurfacesToHeightGrid(terrainSurfaces, heightGrid, gridSize, step);
	      this.repairAnomalousChunkTerrain(
	         terrainSurfaces, waterSurfaces, waterFlags, oceanFlags, coverClasses, heightGrid, gridSize, step, chunkMinY, shell.maxY(), exactWaterData
	      );
      this.applyLandmarkGroundToTerrain(pos, terrainSurfaces, waterSurfaces, waterFlags, chunkMinY, shell.maxY());
      int[] terrainShellBedrockSkinTopYs = this.computeTerrainShellBedrockSkinTopYs(
         terrainSurfaces, heightGrid, gridSize, step, chunkMinY, shell.maxY()
      );
      int[] terrainShellBedrockCurtainBottomYs = this.computeTerrainShellBedrockCurtainBottomYs(
         terrainSurfaces, heightGrid, gridSize, step, chunkMinY, shell.maxY()
      );
      EarthBiomeSource earthBiomeSource = this.biomeSource instanceof EarthBiomeSource typedEarthBiomeSource ? typedEarthBiomeSource : null;
      EarthChunkGenerator.ChunkBiomeClimateCache climateCache = FAST_FULL_CHUNK && earthBiomeSource != null
         ? new EarthChunkGenerator.ChunkBiomeClimateCache(pos, this.settings.worldScale())
         : null;
      Holder<Biome>[] biomeCache = newBiomeCache(CHUNK_AREA);
      this.fillChunkTerrainMetricsAndBiomes(
         pos,
         step,
         gridSize,
         heightGrid,
         terrainSurfaces,
         waterSurfaces,
         waterFlags,
         coverClasses,
         visualCoverClasses,
         oceanFlags,
         slopeDiffs,
         convexities,
         biomeCache,
         earthBiomeSource,
         climateCache,
         null,
         shell.biomeCache()
      );

      EarthChunkGenerator.ChunkGenerationContext exactContext = EarthChunkGenerator.ChunkGenerationContext.capture(
         pos, chunkMinY, shell.maxY(), terrainSurfaces, waterSurfaces, waterFlags, surfaceCoverClasses, biomeCache, null, shell.generationStamp()
      );
      EarthChunkGenerator.PreparedChunkDetail delayedDetail = this.preparePostRefinementChunkDetail(exactContext);
      if (TellusElevationSource.unavailableTileReads() != unavailableElevationReads) {
         throw new ElevationUnavailableException(pos.toString());
      }
      return new EarthChunkGenerator.PreparedTerrainRefinement(
         shell,
         exactContext,
         delayedDetail,
         terrainSurfaces,
         terrainShellBedrockSkinTopYs,
         terrainShellBedrockCurtainBottomYs,
         waterSurfaces,
         waterFlags,
         coverClasses,
         visualCoverClasses,
         surfaceCoverClasses,
         oceanFlags,
         slopeDiffs,
         convexities
      );
   }

   private EarthChunkGenerator.PreparedChunkDetail preparePostRefinementChunkDetail(EarthChunkGenerator.ChunkGenerationContext context) {
      return this.buildPreparedChunkDetail(context, true, true, true, true, this.resolveFullChunkOsmQueryMode());
   }

   private WaterSurfaceResolver.WaterChunkData buildExactWaterChunkData(ChunkPos pos, int[] terrainSurfaces, int[] rawCoverClasses) {
      if (this.settings.enableWater()) {
         // Preserve dry-bank metadata through deferred terrain refinement too.
         return this.resolveExactChunkWaterData(pos);
      }
      int[] waterTerrain = Arrays.copyOf(terrainSurfaces, terrainSurfaces.length);
      int[] waterSurfaces = Arrays.copyOf(terrainSurfaces, terrainSurfaces.length);
      byte[] waterFlags = new byte[terrainSurfaces.length];
      int chunkMinX = pos.getMinBlockX();
      int chunkMinZ = pos.getMinBlockZ();

      for (int localX = 0; localX < CHUNK_SIDE; localX++) {
         int worldX = chunkMinX + localX;

         for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
            int worldZ = chunkMinZ + localZ;
            int index = chunkIndex(localX, localZ);
            WaterSurfaceResolver.WaterColumnData column = this.waterResolver.resolveColumnData(
               worldX, worldZ, rawCoverClasses[index], this.settings.worldScale()
            );
            WaterSurfaceResolver.WaterfallInfo waterfall = this.waterResolver.resolveWaterfallInfo(worldX, worldZ);
            if (waterfall.waterfall()) {
               waterTerrain[index] = waterfall.terrainSurface();
               waterSurfaces[index] = waterfall.upstreamSurface();
               waterFlags[index] = 3;
            } else {
               waterTerrain[index] = column.terrainSurface();
               waterSurfaces[index] = column.waterSurface();
               waterFlags[index] = !column.hasWater() ? 0 : (byte)(column.isOcean() ? 2 : 1);
            }
         }
      }

      return WaterSurfaceResolver.WaterChunkData.fromArrays(waterTerrain, waterSurfaces, waterFlags, false);
   }

   private static void copyChunkTerrainSurfaces(int[] heightGrid, int gridSize, int step, int[] terrainSurfaces) {
      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         int sourceIndex = (localZ + step) * gridSize + step;
         System.arraycopy(heightGrid, sourceIndex, terrainSurfaces, localZ * CHUNK_SIDE, CHUNK_SIDE);
      }
   }

   private static void copyChunkTerrainSurfacesToHeightGrid(int[] terrainSurfaces, int[] heightGrid, int gridSize, int step) {
      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         int targetIndex = (localZ + step) * gridSize + step;
         System.arraycopy(terrainSurfaces, localZ * CHUNK_SIDE, heightGrid, targetIndex, CHUNK_SIDE);
      }
   }

   private EarthChunkGenerator.TerrainShellColumnFillResult fillTerrainShellColumns(
      ChunkPos pos,
      int chunkMinY,
      int chunkMaxY,
      int step,
      int gridSize,
      int[] heightGrid,
      WaterSurfaceResolver.WaterChunkData waterData,
      int[] terrainSurfaces,
      int[] waterSurfaces,
      boolean[] waterFlags,
      int[] coverClasses,
      int[] visualCoverClasses,
      int[] surfaceCoverClasses,
      boolean[] oceanFlags
   ) {
      long surfaceCoverResolveNs = 0L;
      int shellCoverMisses = 0;
      int shellVisualCoverMisses = 0;
      int chunkMinX = pos.getMinBlockX();
      int chunkMinZ = pos.getMinBlockZ();
      boolean waterEnabled = this.settings.enableWater();

      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         int worldZ = chunkMinZ + localZ;
         int gridRowIndex = (localZ + step) * gridSize + step;
         int rowIndex = localZ * CHUNK_SIDE;

         for (int localX = 0; localX < CHUNK_SIDE; localX++) {
            int worldX = chunkMinX + localX;
            int index = rowIndex + localX;
            int sampledCoverClass = this.sampleCoverClassForTerrainShell(worldX, worldZ);
            if (sampledCoverClass == Integer.MIN_VALUE) {
               shellCoverMisses++;
               sampledCoverClass = 0;
            }

            int coverClass = this.resolveEffectiveCoverClassForTerrain(sampledCoverClass);
            EarthChunkGenerator.ColumnHeights column = this.resolveColumnHeights(
               worldX, worldZ, localX, localZ, chunkMinY, chunkMaxY, coverClass, waterData, heightGrid[gridRowIndex + localX]
            );
            boolean hasWater = column.hasWater();
            boolean dryEsaWater = this.isDryOsmEsaWater(coverClass, hasWater);
            coverClass = this.resolveDryOsmTerrainCoverClass(worldX, worldZ, coverClass, hasWater);
            coverClasses[index] = coverClass;
            long surfaceCoverStartNs = beginFullChunkProfiling();
            int visualCoverClass = dryEsaWater ? coverClass : this.sampleVisualCoverClassForTerrainShell(worldX, worldZ, coverClass);
            if (visualCoverClass == Integer.MIN_VALUE) {
               shellVisualCoverMisses++;
               visualCoverClass = coverClass;
            }

            visualCoverClasses[index] = visualCoverClass;
            surfaceCoverClasses[index] = this.resolveSurfaceCoverClassForTerrain(coverClass, visualCoverClass);
            surfaceCoverResolveNs += elapsedFullChunkProfilingSince(surfaceCoverStartNs);
            terrainSurfaces[index] = column.terrainSurface();
            waterSurfaces[index] = column.waterSurface();
            waterFlags[index] = hasWater;
            oceanFlags[index] = hasWater && waterEnabled && waterData.isOcean(localX, localZ);
         }
      }

      return new EarthChunkGenerator.TerrainShellColumnFillResult(surfaceCoverResolveNs, shellCoverMisses, shellVisualCoverMisses);
   }

   private void fillBaseTerrainRawCoverClasses(ChunkPos pos, int[] rawCoverClasses) {
      int chunkMinX = pos.getMinBlockX();
      int chunkMinZ = pos.getMinBlockZ();

      for (int localX = 0; localX < CHUNK_SIDE; localX++) {
         int worldX = chunkMinX + localX;

         for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
            int worldZ = chunkMinZ + localZ;
            int index = chunkIndex(localX, localZ);
            rawCoverClasses[index] = this.sampleCoverClassForExactTerrain(worldX, worldZ);
         }
      }
   }

   private long fillExactChunkTerrainColumns(
      ChunkPos pos,
      int chunkMinY,
      int chunkMaxY,
      int step,
      int gridSize,
      int[] heightGrid,
      WaterSurfaceResolver.WaterChunkData waterData,
      int[] rawCoverClasses,
      int[] terrainSurfaces,
      int[] waterSurfaces,
      boolean[] waterFlags,
      int[] coverClasses,
      int[] visualCoverClasses,
      int[] surfaceCoverClasses,
      boolean[] oceanFlags,
      boolean profileSurfaceCover
   ) {
      long surfaceCoverResolveNs = 0L;
      int chunkMinX = pos.getMinBlockX();
      int chunkMinZ = pos.getMinBlockZ();
      boolean reuseRawCoverClasses = rawCoverClasses != null;
      boolean waterEnabled = this.settings.enableWater();

      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         int worldZ = chunkMinZ + localZ;
         int gridRowIndex = (localZ + step) * gridSize + step;
         int rowIndex = localZ * CHUNK_SIDE;

         for (int localX = 0; localX < CHUNK_SIDE; localX++) {
            int worldX = chunkMinX + localX;
            int index = rowIndex + localX;
            int rawCoverClass = reuseRawCoverClasses ? rawCoverClasses[index] : this.sampleCoverClassForExactTerrain(worldX, worldZ);
            int coverClass = this.resolveEffectiveCoverClassForTerrain(rawCoverClass);
            EarthChunkGenerator.ColumnHeights column = this.resolveColumnHeights(
               worldX, worldZ, localX, localZ, chunkMinY, chunkMaxY, coverClass, waterData, heightGrid[gridRowIndex + localX]
            );
            boolean hasWater = column.hasWater();
            boolean dryEsaWater = this.isDryOsmEsaWater(coverClass, hasWater);
            coverClass = this.resolveDryOsmTerrainCoverClass(worldX, worldZ, coverClass, hasWater);
            coverClasses[index] = coverClass;
            long surfaceCoverStartNs = profileSurfaceCover ? beginFullChunkProfiling() : 0L;
            int visualCoverClass = dryEsaWater ? coverClass : this.sampleVisualCoverClassForExactTerrain(worldX, worldZ, coverClass);
            visualCoverClasses[index] = visualCoverClass;
            surfaceCoverClasses[index] = this.resolveSurfaceCoverClassForTerrain(coverClass, visualCoverClass);
            if (profileSurfaceCover) {
               surfaceCoverResolveNs += elapsedFullChunkProfilingSince(surfaceCoverStartNs);
            }

            terrainSurfaces[index] = column.terrainSurface();
            waterSurfaces[index] = column.waterSurface();
            waterFlags[index] = hasWater;
            oceanFlags[index] = hasWater && waterEnabled && waterData.isOcean(localX, localZ);
         }
      }

      return surfaceCoverResolveNs;
   }

   private void fillChunkTerrainMetricsAndBiomes(
      ChunkPos pos,
      int step,
      int gridSize,
      int[] heightGrid,
      int[] terrainSurfaces,
      int[] waterSurfaces,
      boolean[] waterFlags,
      int[] coverClasses,
      int[] visualCoverClasses,
      boolean[] oceanFlags,
      int[] slopeDiffs,
      int[] convexities,
      Holder<Biome>[] biomeCache,
      EarthBiomeSource earthBiomeSource,
      EarthChunkGenerator.ChunkBiomeClimateCache climateCache,
      RandomState random,
      Holder<Biome>[] fallbackBiomes
   ) {
      RandomState noiseBiomeRandom = earthBiomeSource == null && fallbackBiomes == null ? Objects.requireNonNull(random, "random") : null;
      int chunkMinX = pos.getMinBlockX();
      int chunkMinZ = pos.getMinBlockZ();

      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         int worldZ = chunkMinZ + localZ;
         int gridRowIndex = (localZ + step) * gridSize + step;
         int rowIndex = localZ * CHUNK_SIDE;

         for (int localX = 0; localX < CHUNK_SIDE; localX++) {
            int worldX = chunkMinX + localX;
            int index = rowIndex + localX;
            int surface = terrainSurfaces[index];
            int gridIndex = gridRowIndex + localX;
            slopeDiffs[index] = sampleSlopeDiffCached(heightGrid, gridSize, step, gridIndex, surface);
            convexities[index] = sampleConvexityCached(heightGrid, gridSize, step, gridIndex, surface);
            if (earthBiomeSource != null) {
               biomeCache[index] = earthBiomeSource.getBiomeAtBlock(
                  worldX,
                  worldZ,
                  coverClasses[index],
                  visualCoverClasses[index],
                  new WaterSurfaceResolver.WaterColumnData(waterFlags[index], oceanFlags[index], surface, waterSurfaces[index]),
                  climateCache != null ? climateCache.resolve(worldX, worldZ, EarthChunkGenerator::sampleKoppenCode) : null
               );
            } else if (fallbackBiomes != null) {
               biomeCache[index] = fallbackBiomes[index];
            } else {
               biomeCache[index] = WorldgenApi.sampleBiome(this.biomeSource,
                  QuartPos.fromBlock(worldX), QuartPos.fromBlock(surface), QuartPos.fromBlock(worldZ), noiseBiomeRandom
               );
            }
         }
      }
   }

   private void applyPreparedTerrainRefinement(ServerLevel level, LevelChunk chunk, EarthChunkGenerator.PreparedTerrainRefinement refinement) {
      long chunkKey = MinecraftVersionCompat.packChunkPos(
         MinecraftVersionCompat.chunkX(chunk.getPos()), MinecraftVersionCompat.chunkZ(chunk.getPos())
      );
      if (!Objects.equals(this.terrainGenerationStamps.get(chunkKey), refinement.generationStamp())) {
         EarthChunkGenerator.TerrainStreamingPerf.recordRefinementStaleDrop();
         return;
      }

      this.applyTerrainRefinementPatch(chunk, refinement);
      this.applyUndergroundStructureProtection(level.structureManager(), chunk, refinement.terrainSurfaces(), true);
      this.carveStructureClearanceVolumes(level.structureManager(), chunk);
      this.applyPreparedChunkDetail(
         level, chunk, refinement.delayedDetail(), this.abandonedCampPieces(level.registryAccess(), level.structureManager(), chunk.getPos())
      );
      this.applyRealtimeSnowCover(level, chunk);
      // Last, as in applyBiomeDecoration, so the landmark wins over trees and lamps.
      this.placeLandmarks(level, chunk);
      this.terrainGenerationStamps.remove(chunkKey, refinement.generationStamp());
      // The patch wrote straight into the loaded chunk, so players who already received it would keep the shell.
      List<ServerPlayer> players = level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false);
      if (!players.isEmpty()) {
         ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
         for (ServerPlayer player : players) {
            player.connection.send(packet);
         }
      }
   }

   private void applyTerrainRefinementPatch(ChunkAccess chunk, EarthChunkGenerator.PreparedTerrainRefinement refinement) {
      ChunkPos pos = chunk.getPos();
      int chunkMinY = MinecraftVersionCompat.minY(chunk);
      int chunkMaxY = chunkMinY + chunk.getHeight() - 1;
      int chunkMinX = pos.getMinBlockX();
      int chunkMinZ = pos.getMinBlockZ();
      MutableBlockPos cursor = new MutableBlockPos();
      EarthChunkGenerator.TerrainShellBuildResult shell = refinement.shell();
      EarthChunkGenerator.FullChunkOceanBeachCache oceanBeachCache = this.buildFullChunkOceanBeachCache(
         chunkMinX, chunkMinZ, refinement.waterFlags(), refinement.oceanFlags()
      );
      boolean useFastSurfacePalette = FAST_FULL_CHUNK && FULL_CHUNK_SURFACE_MODE == EarthChunkGenerator.SurfaceMode.TWO_TIER;
      EarthChunkGenerator.SurfaceApplyProfiler profiler = new EarthChunkGenerator.SurfaceApplyProfiler();
      int[] shellTerrainSurfaces = shell.terrainSurfaces();
      int[] shellWaterSurfaces = shell.waterSurfaces();
      boolean[] shellWaterFlags = shell.waterFlags();
      int[] refinedTerrainSurfaces = refinement.terrainSurfaces();
      int[] refinedWaterSurfaces = refinement.waterSurfaces();
      boolean[] refinedWaterFlags = refinement.waterFlags();
      int[] refinedSlopeDiffs = refinement.slopeDiffs();
      int[] refinedConvexities = refinement.convexities();
      int[] refinedSurfaceCoverClasses = refinement.surfaceCoverClasses();
      Holder<Biome>[] refinedBiomes = refinement.context().biomeCache();
      boolean thinShellTerrain = this.settings.usesTerrainShell();

      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         int worldZ = chunkMinZ + localZ;
         int rowIndex = localZ * CHUNK_SIDE;

         for (int localX = 0; localX < CHUNK_SIDE; localX++) {
            int worldX = chunkMinX + localX;
            int index = rowIndex + localX;
            int oldSurface = shellTerrainSurfaces[index];
            int newSurface = refinedTerrainSurfaces[index];
            int oldTop = shellWaterFlags[index] ? Math.max(oldSurface, shellWaterSurfaces[index]) : oldSurface;
            int newTop = refinedWaterFlags[index] ? Math.max(newSurface, refinedWaterSurfaces[index]) : newSurface;
            int oldSupportBottom = thinShellTerrain
               ? this.resolveThinShellSupportBottomY(
                  localX,
                  localZ,
                  worldX,
                  worldZ,
                  oldSurface,
                  oldTop,
                  shellTerrainSurfaces,
                  chunkMinY,
                  shell.waterFlags()[index] && shell.oceanFlags()[index] && shell.waterSurfaces()[index] > oldSurface
               )
               : oldSurface;
            int newSupportBottom = thinShellTerrain
               ? this.resolveThinShellSupportBottomY(
                  localX,
                  localZ,
                  worldX,
                  worldZ,
                  newSurface,
                  newTop,
                  refinedTerrainSurfaces,
                  chunkMinY,
                  refinedWaterFlags[index] && refinement.oceanFlags()[index] && refinedWaterSurfaces[index] > newSurface
               )
               : newSurface;
            int rewriteBottom = Mth.clamp(
               Math.min(Math.min(oldSurface, newSurface), Math.min(oldSupportBottom, newSupportBottom))
                  - SurfaceMaterialDepthPolicy.MAX_FULL_SNOW_DEPTH,
               chunkMinY,
               chunkMaxY
            );
            int rewriteTop = Mth.clamp(Math.max(oldTop, newTop) + 2, chunkMinY, chunkMaxY);
            boolean refinedUnderwater = refinedWaterFlags[index] && refinedWaterSurfaces[index] > newSurface;
            boolean refinedOceanSupport = refinedUnderwater && refinement.oceanFlags()[index];
            BlockState mountainMassFill = refinedUnderwater
               ? null
               : this.resolveMountainMassFillBlock(
                  refinedBiomes[index],
                  refinedSurfaceCoverClasses[index],
                  newSurface,
                  refinedSlopeDiffs[index],
                  refinedConvexities[index],
                  worldX,
                  worldZ
               );
            boolean surfaceSnow = !refinedUnderwater && newSurface >= this.seaLevel
               && this.shouldRetainSurfaceSnow(refinedSurfaceCoverClasses[index], worldX, worldZ);
            boolean fullDepthSurfaceSnow = surfaceSnow
               && SnowSlopePolicy.hasFullCoverage(this.sampleSnowSlopeDegrees(worldX, worldZ));
            boolean glacierHollow = surfaceSnow
               && MountainSurfaceRules.isGlacierHollow(refinedSlopeDiffs[index], refinedConvexities[index]);

            for (int y = rewriteBottom; y <= rewriteTop; y++) {
               cursor.set(worldX, y, worldZ);
               setChunkBlockStateDiscardingBlockEntity(chunk, cursor, AIR_STATE);
            }

            if (thinShellTerrain) {
               int supportBottomY = newSupportBottom;
               int fillTopY = Math.min(chunkMaxY - 1, newSurface - 1);
               int bedrockY = Math.min(supportBottomY, fillTopY);
               boolean surfaceRelativeGeology = this.settings.usesTerrainShell();
               int columnDeepslateStart = surfaceRelativeGeology
                  ? TellusCaveDepthMapper.actualDeepslateBoundaryY(newSurface, bedrockY + 1, this.seaLevel)
                  : this.minY + 64;
               if (bedrockY >= chunkMinY && bedrockY < chunkMaxY) {
                  cursor.set(worldX, bedrockY, worldZ);
                  setChunkBlockStateDiscardingBlockEntity(chunk, cursor, BEDROCK_STATE);
               }
               for (int y = bedrockY + 1; y <= fillTopY; y++) {
                  BlockState fill = mountainMassFill != null ? mountainMassFill : STONE_STATE;
                  if (y < columnDeepslateStart && (surfaceRelativeGeology || mountainMassFill == null && !refinedOceanSupport)) {
                     fill = DEEPSLATE_STATE;
                  }
                  cursor.set(worldX, y, worldZ);
                  setChunkBlockStateDiscardingBlockEntity(chunk, cursor, fill);
               }
            } else {
               for (int y = rewriteBottom; y <= newSurface; y++) {
                  cursor.set(worldX, y, worldZ);
                  setChunkBlockStateDiscardingBlockEntity(
                     chunk, cursor, mountainMassFill != null ? mountainMassFill : y < this.minY + 64 ? DEEPSLATE_STATE : STONE_STATE
                  );
               }
            }

            if (refinedWaterFlags[index] && newSurface < refinedWaterSurfaces[index]) {
               for (int y = newSurface + 1; y <= refinedWaterSurfaces[index]; y++) {
                  cursor.set(worldX, y, worldZ);
                  setChunkBlockStateDiscardingBlockEntity(chunk, cursor, WATER_STATE);
               }
               this.applyInlandFlowSurface(chunk, null, cursor, worldX, worldZ, refinedWaterSurfaces[index]);
               if (this.isWaterfallSourceColumn(worldX, worldZ, refinedWaterSurfaces[index])) {
                  cursor.set(worldX, refinedWaterSurfaces[index], worldZ);
                  MinecraftVersionCompat.markPosForPostProcessing(chunk, cursor);
               }
            }

            if (thinShellTerrain) {
               this.applyThinShellSurface(
                  chunk,
                  cursor,
                  worldX,
                  worldZ,
                  newSurface,
                  chunkMinY,
                  refinedUnderwater,
                  surfaceSnow,
                  refinedBiomes[index],
                  refinedSlopeDiffs[index],
                  refinedConvexities[index],
                  refinedSurfaceCoverClasses[index],
                  oceanBeachCache,
                  profiler,
                  useFastSurfacePalette
               );
            } else {
               this.applySurface(
                  chunk,
                  cursor,
                  worldX,
                  worldZ,
                  newSurface,
                  chunkMinY,
                  refinedUnderwater,
                  refinedBiomes[index],
                  refinedSlopeDiffs[index],
                  refinedConvexities[index],
                  refinedSurfaceCoverClasses[index],
                  oceanBeachCache,
                  profiler,
                  useFastSurfacePalette
               );
            }

            if (surfaceSnow) {
               this.applySnowCover(chunk, cursor, worldX, worldZ, newSurface, chunkMinY, fullDepthSurfaceSnow, glacierHollow);
            }
         }
      }

      if (thinShellTerrain) {
         this.applyTerrainShellBedrockSkin(
            pos,
            refinedTerrainSurfaces,
            refinement.terrainShellBedrockSkinTopYs(),
            refinement.terrainShellBedrockCurtainBottomYs(),
            chunkMinY,
            (localX, localZ, worldX, worldZ, y) -> {
               cursor.set(worldX, y, worldZ);
               setChunkBlockStateDiscardingBlockEntity(chunk, cursor, BEDROCK_STATE);
            }
         );
      }
   }

   private void applyPreparedChunkDetail(
      WorldGenLevel level, ChunkAccess chunk, EarthChunkGenerator.PreparedChunkDetail detail, List<BoundingBox> campPieces
   ) {
      long applyStartNs = EarthChunkGenerator.ChunkDetailPerf.now();
      EarthChunkGenerator.ChunkGenerationContext context = detail.context();
      EarthChunkGenerator.PreparedChunkBuildings preparedBuildings = detail.preparedBuildings();
      if (detail.placeBuildings() && preparedBuildings != null && !preparedBuildings.isEmpty()) {
         this.applyPreparedBuildingTerrainToChunk(level, chunk, preparedBuildings);
         this.placePreparedBuildings(level, chunk, preparedBuildings);
      }

      EarthChunkGenerator.OsmRoadQueryResult roadQuery = detail.roadQuery();
      if (roadQuery != null && !roadQuery.features().isEmpty()) {
         int[] terrainSurfaces = context.copyTerrainSurfaces();
         int[] waterSurfaces = context.copyWaterSurfaces();
         boolean[] waterFlags = context.copyWaterFlags();
         if (preparedBuildings != null && !preparedBuildings.isEmpty()) {
            this.applyPreparedBuildingsToTerrain(preparedBuildings, terrainSurfaces, waterSurfaces, waterFlags, context.minY(), context.maxY());
         }
         this.applyLandmarkGroundToTerrain(context.pos(), terrainSurfaces, waterSurfaces, waterFlags, context.minY(), context.maxY());

         this.applyOsmRoadOverlay(
            level,
            chunk,
            context.pos(),
            terrainSurfaces,
            waterSurfaces,
            waterFlags,
            context.minY(),
            context.maxY(),
            preparedBuildings,
            roadQuery
         );
         this.placePreparedRoadLights(level, chunk);
      }

      List<EarthChunkGenerator.PreparedTreePlacement> treePlacements = detail.treePlacements();
      if (!treePlacements.isEmpty()) {
         long treeApplyStartNs = beginFullChunkProfiling();
         this.applyPreparedTreePlacements(level, chunk, treePlacements, campPieces);
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.DECORATION_TREES_DEFER_APPLY, treeApplyStartNs);
      }

      EarthChunkGenerator.ChunkDetailPerf.recordDetailApply(EarthChunkGenerator.ChunkDetailPerf.elapsedSince(applyStartNs));
   }

   public void prefetchForChunk(int chunkX, int chunkZ) {
      this.prefetchForChunk(chunkX, chunkZ, true);
   }

   public void prefetchForChunk(int chunkX, int chunkZ, boolean includeRoadsPrefetch) {
      this.prefetchForChunk(chunkX, chunkZ, includeRoadsPrefetch, true);
   }

   public void prefetchForChunk(int chunkX, int chunkZ, boolean includeRoadsPrefetch, boolean includeDetailedWaterPrefetch) {
      this.prefetchForChunk(chunkX, chunkZ, includeRoadsPrefetch, includeDetailedWaterPrefetch, true);
   }

   public void prefetchForChunk(
      int chunkX, int chunkZ, boolean includeRoadsPrefetch, boolean includeDetailedWaterPrefetch, boolean includeBuildingsPrefetch
   ) {
      this.prefetchForChunk(chunkX, chunkZ, includeRoadsPrefetch, includeDetailedWaterPrefetch, includeBuildingsPrefetch, this.settings.worldScale());
   }

   public void prefetchForChunk(
      int chunkX,
      int chunkZ,
      boolean includeRoadsPrefetch,
      boolean includeDetailedWaterPrefetch,
      boolean includeBuildingsPrefetch,
      double previewResolutionMeters
   ) {
      this.prefetchForChunk(chunkX, chunkZ, includeRoadsPrefetch, includeDetailedWaterPrefetch, includeBuildingsPrefetch, previewResolutionMeters, true);
   }

   public void prefetchForChunk(
      int chunkX,
      int chunkZ,
      boolean includeRoadsPrefetch,
      boolean includeDetailedWaterPrefetch,
      boolean includeBuildingsPrefetch,
      double previewResolutionMeters,
      boolean allowInlineExecution
   ) {
      TellusWorldgenSources.prefetchForChunk(
         new ChunkPos(chunkX, chunkZ),
         this.settings,
         includeRoadsPrefetch,
         includeDetailedWaterPrefetch,
         includeBuildingsPrefetch,
         previewResolutionMeters,
         allowInlineExecution
      );
   }

   public CompletableFuture<Void> prefetchForArea(
      int minBlockX,
      int minBlockZ,
      int maxBlockX,
      int maxBlockZ,
      boolean includeRoadsPrefetch,
      boolean includeBuildingsPrefetch,
      double previewResolutionMeters
   ) {
      return TellusWorldgenSources.prefetchForArea(
         minBlockX,
         minBlockZ,
         maxBlockX,
         maxBlockZ,
         this.settings,
         includeRoadsPrefetch,
         includeBuildingsPrefetch,
         previewResolutionMeters
      );
   }

   public List<RoadFeature> fetchOsmRoadsForArea(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, int marginBlocks) {
      return this.fetchOsmRoadsForArea(minBlockX, minBlockZ, maxBlockX, maxBlockZ, marginBlocks, OsmQueryMode.BLOCKING);
   }

   public List<RoadFeature> fetchOsmRoadsForArea(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, int marginBlocks, OsmQueryMode mode) {
      return this.fetchOsmRoadsForAreaDetailed(minBlockX, minBlockZ, maxBlockX, maxBlockZ, marginBlocks, mode).features();
   }

   public EarthChunkGenerator.OsmRoadQueryResult fetchOsmRoadsForAreaDetailed(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, int marginBlocks, OsmQueryMode mode
   ) {
      if (!this.settings.enableRoads()) {
         return new EarthChunkGenerator.OsmRoadQueryResult(List.of(), false);
      } else {
         double worldScale = this.settings.worldScale();
         if (!(worldScale <= 0.0) && !(worldScale > OSM_ROAD_MAX_SCALE)) {
            OsmQueryMode queryMode = mode == null ? OsmQueryMode.BLOCKING : mode;
            TellusOsmRoadSource.RoadQueryResult result = OSM_ROAD_SOURCE.roadsForAreaWithStatus(
               minBlockX, minBlockZ, maxBlockX, maxBlockZ, worldScale, Math.max(0, marginBlocks), queryMode
            );
            return new EarthChunkGenerator.OsmRoadQueryResult(result.features(), result.hadCacheMiss());
         } else {
            return new EarthChunkGenerator.OsmRoadQueryResult(List.of(), false);
         }
      }
   }

   public EarthChunkGenerator.OsmRoadAreaQueryResult fetchOsmRoadAreasForAreaDetailed(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, int marginBlocks, OsmQueryMode mode
   ) {
      if (!this.settings.enableRoads()) {
         return new EarthChunkGenerator.OsmRoadAreaQueryResult(List.of(), false);
      } else {
         double worldScale = this.settings.worldScale();
         if (!(worldScale <= 0.0) && !(worldScale > OSM_ROAD_MAX_SCALE)) {
            OsmQueryMode queryMode = mode == null ? OsmQueryMode.BLOCKING : mode;
            TellusOsmRoadSource.RoadAreaQueryResult result = OSM_ROAD_SOURCE.roadAreasForAreaWithStatus(
               minBlockX, minBlockZ, maxBlockX, maxBlockZ, worldScale, Math.max(0, marginBlocks), queryMode
            );
            return new EarthChunkGenerator.OsmRoadAreaQueryResult(result.features(), result.hadCacheMiss());
         } else {
            return new EarthChunkGenerator.OsmRoadAreaQueryResult(List.of(), false);
         }
      }
   }

   public EarthChunkGenerator.OsmStreetLightQueryResult fetchOsmStreetLightsForAreaDetailed(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, int marginBlocks, OsmQueryMode mode
   ) {
      EarthChunkGenerator.OsmStreetLightQueryResult result = this.fetchOsmRoadPointsForAreaDetailed(
         minBlockX, minBlockZ, maxBlockX, maxBlockZ, marginBlocks, mode
      );
      return new EarthChunkGenerator.OsmStreetLightQueryResult(filterRoadPointsByKind(result.features(), RoadPointKind.STREET_LIGHT), result.hadCacheMisses());
   }

   public EarthChunkGenerator.OsmStreetLightQueryResult fetchOsmRoadPointsForAreaDetailed(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, int marginBlocks, OsmQueryMode mode
   ) {
      if (!this.settings.enableRoads()) {
         return new EarthChunkGenerator.OsmStreetLightQueryResult(List.of(), false);
      } else {
         double worldScale = this.settings.worldScale();
         if (!(worldScale <= 0.0) && !(worldScale > OSM_ROAD_MAX_SCALE) && OSM_INFRASTRUCTURE_SOURCE.available()) {
            OsmQueryMode queryMode = mode == null ? OsmQueryMode.BLOCKING : mode;
            TellusOsmInfrastructureSource.StreetLightQueryResult result = OSM_INFRASTRUCTURE_SOURCE.roadPointsForAreaWithStatus(
               minBlockX, minBlockZ, maxBlockX, maxBlockZ, worldScale, Math.max(0, marginBlocks), queryMode
            );
            return new EarthChunkGenerator.OsmStreetLightQueryResult(result.features(), result.hadCacheMiss());
         } else {
            return new EarthChunkGenerator.OsmStreetLightQueryResult(List.of(), false);
         }
      }
   }

   private static List<OsmStreetLightFeature> filterRoadPointsByKind(List<OsmStreetLightFeature> features, RoadPointKind kind) {
      if (features == null || features.isEmpty()) {
         return List.of();
      }

      List<OsmStreetLightFeature> matches = new ArrayList<>();
      for (OsmStreetLightFeature feature : features) {
         if (feature.kind() == kind) {
            matches.add(feature);
         }
      }
      return matches.isEmpty() ? List.of() : matches;
   }

   public List<OsmBuildingFeature> fetchOsmBuildingsForArea(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, int marginBlocks) {
      return this.fetchOsmBuildingsForArea(minBlockX, minBlockZ, maxBlockX, maxBlockZ, marginBlocks, OsmQueryMode.BLOCKING);
   }

   public List<OsmBuildingFeature> fetchOsmBuildingsForArea(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, int marginBlocks, OsmQueryMode mode
   ) {
      return this.fetchOsmBuildingsForAreaDetailed(minBlockX, minBlockZ, maxBlockX, maxBlockZ, marginBlocks, mode).features();
   }

   public EarthChunkGenerator.OsmBuildingQueryResult fetchOsmBuildingsForAreaDetailed(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, int marginBlocks, OsmQueryMode mode
   ) {
      if (!this.settings.enableBuildings()) {
         return new EarthChunkGenerator.OsmBuildingQueryResult(List.of(), false);
      } else {
         double worldScale = this.settings.worldScale();
         if (!(worldScale <= 0.0) && !(worldScale > OSM_BUILDING_MAX_SCALE) && OSM_BUILDING_SOURCE.available()) {
            OsmQueryMode queryMode = mode == null ? OsmQueryMode.BLOCKING : mode;
            TellusOsmBuildingSource.BuildingQueryResult result = OSM_BUILDING_SOURCE.buildingsForAreaWithStatus(
               minBlockX, minBlockZ, maxBlockX, maxBlockZ, worldScale, Math.max(0, marginBlocks), queryMode
            );
            return new EarthChunkGenerator.OsmBuildingQueryResult(result.features(), result.hadCacheMiss());
         } else {
            return new EarthChunkGenerator.OsmBuildingQueryResult(List.of(), false);
         }
      }
   }

   private EarthChunkGenerator.PreparedChunkBuildings prepareChunkBuildings(ChunkPos pos, int[] terrainSurfaces, int chunkMinY, int chunkMaxY, RandomState random) {
      long chunkKey = MinecraftVersionCompat.packChunkPos(MinecraftVersionCompat.chunkX(pos), MinecraftVersionCompat.chunkZ(pos));
      this.preparedChunkBuildings.remove(chunkKey);
      this.clearPreparedChunkStateTracking(chunkKey);
      EarthChunkGenerator.PreparedChunkBuildings prepared = this.buildChunkBuildings(
         pos, terrainSurfaces, chunkMinY, chunkMaxY, (feature, baseY) -> this.sampleBuildingBiome(feature, baseY, random), this.resolveFullChunkOsmQueryMode()
      );
      if (prepared != null && !prepared.isEmpty()) {
         this.preparedChunkBuildings.put(chunkKey, prepared);
         this.markPreparedChunkState(chunkKey);
      }

      return prepared;
   }

   private EarthChunkGenerator.PreparedChunkBuildings buildChunkBuildings(
      ChunkPos pos,
      int[] terrainSurfaces,
      int chunkMinY,
      int chunkMaxY,
      EarthChunkGenerator.BuildingBiomeResolver biomeResolver,
      OsmQueryMode queryMode
   ) {
      if (!this.settings.enableBuildings()) {
         return null;
      } else {
         double worldScale = this.settings.worldScale();
         if (!(worldScale <= 0.0) && !(worldScale > OSM_BUILDING_MAX_SCALE) && OSM_BUILDING_SOURCE.available()) {
            int chunkMinX = pos.getMinBlockX();
            int chunkMinZ = pos.getMinBlockZ();
            int chunkMaxX = chunkMinX + CHUNK_MASK;
            int chunkMaxZ = chunkMinZ + CHUNK_MASK;
            long fetchStartNs = OsmPerf.now();
            EarthChunkGenerator.OsmBuildingQueryResult query = this.fetchOsmBuildingsForAreaDetailed(
               chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ, OSM_BUILDING_QUERY_MARGIN, queryMode
            );
            long fetchNs = OsmPerf.elapsedSince(fetchStartNs);
            if (queryMode == OsmQueryMode.NON_BLOCKING && query.hadCacheMisses() && query.features().isEmpty()) {
               EarthChunkGenerator.ChunkDetailPerf.recordSkippedBlockingFallback();
               OsmPerf.recordFullChunkBuilding(fetchNs, 0L, 0);
               return null;
            }

            List<OsmBuildingFeature> features = query.features();
            if (features.isEmpty()) {
               OsmPerf.recordFullChunkBuilding(fetchNs, 0L, 0);
               return null;
            } else {
               long prepStartNs = OsmPerf.now();
               double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
               int sampleMinX = chunkMinX - OSM_BUILDING_QUERY_MARGIN;
               int sampleMaxX = chunkMaxX + OSM_BUILDING_QUERY_MARGIN;
               int sampleMinZ = chunkMinZ - OSM_BUILDING_QUERY_MARGIN;
               int sampleMaxZ = chunkMaxZ + OSM_BUILDING_QUERY_MARGIN;
               Map<String, EarthChunkGenerator.BuildingGroupScratch> groups = new HashMap<>();
               List<EarthChunkGenerator.RasterizedBuildingFeature> partFeatures = new ArrayList<>();
               List<EarthChunkGenerator.RasterizedBuildingFeature> footprintFeatures = new ArrayList<>();

               for (OsmBuildingFeature feature : features) {
                  String groupId = resolveBuildingGroupId(feature);
                  EarthChunkGenerator.BuildingGroupScratch group = groups.computeIfAbsent(groupId, id -> new EarthChunkGenerator.BuildingGroupScratch());
                  boolean groundContact = feature.kind() != com.yucareux.tellus.world.data.osm.OsmBuildingKind.PART || this.buildingMinHeightBlocks(feature) <= 0;
                  EarthChunkGenerator.AnalyzedBuildingFeature analyzed = this.analyzeChunkBuildingFeature(
                     feature,
                     groupId,
                     terrainSurfaces,
                     chunkMinX,
                     chunkMinZ,
                     sampleMinX,
                     sampleMaxX,
                     sampleMinZ,
                     sampleMaxZ,
                     blocksPerDegree,
                     groundContact
                  );
                  if (analyzed == null) {
                     continue;
                  }

                  IntArrayList groundSamples = analyzed.groundSamples();
                  for (int i = 0; i < groundSamples.size(); i++) {
                     group.groundSamples().add(groundSamples.getInt(i));
                  }

                  EarthChunkGenerator.RasterizedBuildingFeature rasterized = analyzed.rasterized();
                  if (rasterized != null) {
                     for (int index : rasterized.occupiedIndices()) {
                        group.fallbackSamples().add(terrainSurfaces[index]);
                     }

                     if (feature.kind() == com.yucareux.tellus.world.data.osm.OsmBuildingKind.PART) {
                        partFeatures.add(rasterized);
                     } else {
                        footprintFeatures.add(rasterized);
                     }
                  }
               }

               if (partFeatures.isEmpty() && footprintFeatures.isEmpty()) {
                  OsmPerf.recordFullChunkBuilding(fetchNs, OsmPerf.elapsedSince(prepStartNs), features.size());
                  return null;
               } else {
                  for (EarthChunkGenerator.BuildingGroupScratch group : groups.values()) {
                     IntArrayList samples = !group.groundSamples().isEmpty() ? group.groundSamples() : group.fallbackSamples();
                     if (!samples.isEmpty()) {
                        group.setBaseY(this.adjustBuildingBaseY(medianValue(samples), chunkMinY, chunkMaxY));
                     }
                  }

                  EarthChunkGenerator.PreparedChunkBuildings prepared = new EarthChunkGenerator.PreparedChunkBuildings();
                  int[] overlyingPartFloorY = new int[CHUNK_AREA];
                  Arrays.fill(overlyingPartFloorY, Integer.MAX_VALUE);

                  for (EarthChunkGenerator.RasterizedBuildingFeature rasterized : partFeatures) {
                     List<OsmBuildingFeature> entranceNeighbors = this.fetchEntranceNeighbors(rasterized.feature(), worldScale, queryMode);
                     int baseY = worldScale == 1.0
                        ? this.adjustBuildingBaseY(BuildingPlacementSupport.sampleFoundationHeight(rasterized.feature(), entranceNeighbors,
                           worldScale, this::sampleSurfaceHeight), chunkMinY, chunkMaxY)
                        : groups.get(rasterized.groupId()).baseY();
                     BuildingProfile profile = this.resolveBuildingProfile(
                        rasterized.feature(), baseY, biomeResolver.sample(rasterized.feature(), baseY), worldScale
                     );
                     int floorY = this.buildingFloorY(baseY, rasterized.feature());
                     int roofBaseY = this.buildingRoofBaseY(baseY, rasterized.feature(), floorY, profile);
                     int topY = roofBaseY + Math.max(profile.parapetHeight(), profile.roofRise());
                     BuildingBlueprint blueprint = TellusBuildingBlueprints.create(
                        rasterized.groupId(),
                        rasterized.feature(),
                        profile,
                        this.worldSeed,
                        baseY,
                        floorY,
                        roofBaseY,
                        topY,
                        this.fetchEntranceRoads(rasterized.feature(), worldScale, queryMode),
                        worldScale,
                        entranceNeighbors
                     );
                     EarthChunkGenerator.PreparedBuildingFeature preparedFeature = this.prepareLocalBuildingFeature(
                        rasterized, overlyingPartFloorY, blueprint, chunkMinX, chunkMinZ
                     );
                     if (preparedFeature != null) {
                        prepared.addFeature(preparedFeature);

                        for (int index : rasterized.occupiedIndices()) {
                           overlyingPartFloorY[index] = Math.min(overlyingPartFloorY[index], preparedFeature.floorY());
                        }
                     }
                  }

                  for (EarthChunkGenerator.RasterizedBuildingFeature rasterized : footprintFeatures) {
                     List<OsmBuildingFeature> entranceNeighbors = this.fetchEntranceNeighbors(rasterized.feature(), worldScale, queryMode);
                     int baseY = worldScale == 1.0
                        ? this.adjustBuildingBaseY(BuildingPlacementSupport.sampleFoundationHeight(rasterized.feature(), entranceNeighbors,
                           worldScale, this::sampleSurfaceHeight), chunkMinY, chunkMaxY)
                        : groups.get(rasterized.groupId()).baseY();
                     BuildingProfile profile = this.resolveBuildingProfile(
                        rasterized.feature(), baseY, biomeResolver.sample(rasterized.feature(), baseY), worldScale
                     );
                     int floorY = this.buildingFloorY(baseY, rasterized.feature());
                     int roofBaseY = this.buildingRoofBaseY(baseY, rasterized.feature(), floorY, profile);
                     int topY = roofBaseY + Math.max(profile.parapetHeight(), profile.roofRise());
                     BuildingBlueprint blueprint = TellusBuildingBlueprints.create(
                        rasterized.groupId(),
                        rasterized.feature(),
                        profile,
                        this.worldSeed,
                        baseY,
                        floorY,
                        roofBaseY,
                        topY,
                        this.fetchEntranceRoads(rasterized.feature(), worldScale, queryMode),
                        worldScale,
                        entranceNeighbors
                     );
                     EarthChunkGenerator.PreparedBuildingFeature preparedFeature = this.prepareLocalBuildingFeature(
                        rasterized, overlyingPartFloorY, blueprint, chunkMinX, chunkMinZ
                     );
                     if (preparedFeature != null) {
                        prepared.addFeature(preparedFeature);
                     }
                  }

                  OsmPerf.recordFullChunkBuilding(fetchNs, OsmPerf.elapsedSince(prepStartNs), features.size());
                  return prepared.isEmpty() ? null : prepared;
               }
            }
         } else {
            return null;
         }
      }
   }

   private int adjustBuildingBaseY(int sampledBaseY, int chunkMinY, int chunkMaxY) {
      int maxBaseY = Math.max(chunkMinY, chunkMaxY - 1);
      return Mth.clamp(sampledBaseY + OSM_BUILDING_BASE_Y_OFFSET, chunkMinY, maxBaseY);
   }

   private EarthChunkGenerator.AnalyzedBuildingFeature analyzeChunkBuildingFeature(
      OsmBuildingFeature feature,
      String groupId,
      int[] terrainSurfaces,
      int chunkMinX,
      int chunkMinZ,
      int sampleMinX,
      int sampleMaxX,
      int sampleMinZ,
      int sampleMaxZ,
      double blocksPerDegree,
      boolean collectGroundSamples
   ) {
      double worldScale = this.settings.worldScale();
      int minX = Math.max(sampleMinX, Mth.floor(feature.minBlockX(blocksPerDegree) - 1.0));
      int maxX = Math.min(sampleMaxX, Mth.ceil(feature.maxBlockX(blocksPerDegree) + 1.0));
      int minZ = Math.max(sampleMinZ, Mth.floor(feature.minBlockZ(worldScale) - 1.0));
      int maxZ = Math.min(sampleMaxZ, Mth.ceil(feature.maxBlockZ(worldScale) + 1.0));
      if (maxX < minX || maxZ < minZ) {
         return null;
      } else {
         int padding = BUILDING_SLICE_PADDING;
         int extSide = CHUNK_SIDE + padding * 2;
         int extMinX = chunkMinX - padding;
         int extMinZ = chunkMinZ - padding;
         int extMaxX = chunkMinX + CHUNK_MASK + padding;
         int extMaxZ = chunkMinZ + CHUNK_MASK + padding;
         boolean[] occupied = new boolean[CHUNK_AREA];
         IntArrayList occupiedIndices = new IntArrayList();
         IntArrayList groundSamples = new IntArrayList();
         boolean[] extOccupied = new boolean[extSide * extSide];
         int partCount = feature.partCount();
         double[][] partXs = new double[partCount][];
         double[][] partZs = new double[partCount][];
         int rasterMinX = Integer.MAX_VALUE;
         int rasterMaxX = Integer.MIN_VALUE;
         int rasterMinZ = Integer.MAX_VALUE;
         int rasterMaxZ = Integer.MIN_VALUE;

         for (int part = 0; part < partCount; part++) {
            int pointCount = feature.pointCount(part);
            double[] xs = new double[pointCount];
            double[] zs = new double[pointCount];

            for (int point = 0; point < pointCount; point++) {
               double worldX = feature.lonAt(part, point) * blocksPerDegree - 0.5;
               double worldZ = EarthProjection.latToBlockZ(feature.latAt(part, point), worldScale) - 0.5;
               xs[point] = worldX;
               zs[point] = worldZ;
               rasterMinX = Math.min(rasterMinX, Mth.floor(worldX));
               rasterMaxX = Math.max(rasterMaxX, Mth.ceil(worldX));
               rasterMinZ = Math.min(rasterMinZ, Mth.floor(worldZ));
               rasterMaxZ = Math.max(rasterMaxZ, Mth.ceil(worldZ));
            }

            partXs[part] = xs;
            partZs[part] = zs;
         }

         int clampedMinX = Math.max(minX, rasterMinX);
         int clampedMaxX = Math.min(maxX, rasterMaxX);
         int clampedMinZ = Math.max(minZ, rasterMinZ);
         int clampedMaxZ = Math.min(maxZ, rasterMaxZ);
         if (clampedMaxX < clampedMinX || clampedMaxZ < clampedMinZ) {
            return null;
         }

         ScanlinePolygonRasterizer.fill(partXs, partZs, clampedMinX, clampedMinZ, clampedMaxX, clampedMaxZ, (worldX, worldZ) -> {
               boolean inChunk = worldX >= chunkMinX && worldX < chunkMinX + CHUNK_SIDE && worldZ >= chunkMinZ && worldZ < chunkMinZ + CHUNK_SIDE;
               if (inChunk) {
                  int index = chunkIndex(worldX - chunkMinX, worldZ - chunkMinZ);
                  if (!occupied[index]) {
                     occupied[index] = true;
                     occupiedIndices.add(index);
                     if (collectGroundSamples) {
                        groundSamples.add(terrainSurfaces[index]);
                     }
                  }
               } else if (collectGroundSamples) {
                  groundSamples.add(this.sampleSurfaceHeight(worldX, worldZ));
               }

               if (worldX >= extMinX && worldX <= extMaxX && worldZ >= extMinZ && worldZ <= extMaxZ) {
                  extOccupied[extIndex(worldX - extMinX, worldZ - extMinZ, extSide)] = true;
               }
            });

         if (occupiedIndices.isEmpty()) {
            return new EarthChunkGenerator.AnalyzedBuildingFeature(null, groundSamples);
         } else {
            EarthChunkGenerator.BuildingBoundaryInfo boundaryInfo = this.computeBuildingBoundaryInfo(occupied, extOccupied, extSide);
            return new EarthChunkGenerator.AnalyzedBuildingFeature(
               new EarthChunkGenerator.RasterizedBuildingFeature(
                  feature,
                  groupId,
                  occupied,
                  occupiedIndices.toIntArray(),
                  boundaryInfo.boundary(),
                  boundaryInfo.boundaryDistance()
               ),
               groundSamples
            );
         }
      }
   }

   private EarthChunkGenerator.PreparedBuildingFeature prepareLocalBuildingFeature(
      EarthChunkGenerator.RasterizedBuildingFeature rasterized,
      int[] overlyingPartFloorY,
      BuildingBlueprint blueprint,
      int chunkMinX,
      int chunkMinZ
   ) {
      boolean[] occupied = new boolean[CHUNK_AREA];
      IntArrayList occupiedIndices = new IntArrayList(rasterized.occupiedIndices().length);
      int minHeightBlocks = this.buildingMinHeightBlocks(rasterized.feature());
      int[] columnTopY = new int[CHUNK_AREA];
      Arrays.fill(columnTopY, Integer.MIN_VALUE);

      for (int index : rasterized.occupiedIndices()) {
         int localX = index % CHUNK_SIDE;
         int localZ = index / CHUNK_SIDE;
         int worldX = chunkMinX + localX;
         int worldZ = chunkMinZ + localZ;
         int topY = blueprint.roofTopY(worldX, worldZ, rasterized.boundaryDistance(index));
         if (this.usesParapet(blueprint.profile().roofProfile()) && rasterized.boundary(index)) {
            topY += blueprint.parapetHeight(rasterized.boundaryDistance(index));
         }

         if (overlyingPartFloorY != null) {
            topY = BuildingPlacementSupport.capLowerColumnTopY(blueprint.floorY(), topY, overlyingPartFloorY[index]);
         }

         if (topY >= blueprint.floorY()) {
            occupied[index] = true;
            occupiedIndices.add(index);
            columnTopY[index] = topY;
         }
      }

      if (occupiedIndices.isEmpty()) {
         return null;
      } else {
         boolean groundContact = rasterized.feature().kind() != com.yucareux.tellus.world.data.osm.OsmBuildingKind.PART || minHeightBlocks <= 0;
         return new EarthChunkGenerator.PreparedBuildingFeature(
            occupied,
            occupiedIndices.toIntArray(),
            rasterized.boundary(),
            rasterized.boundaryDistance(),
            columnTopY,
            blueprint,
            groundContact,
            new TellusBuildingInteriors(BuildingFloorPlan.create(blueprint, rasterized.feature(), this.settings.worldScale()))
         );
      }
   }

   private void applyPreparedBuildingsToTerrain(
      EarthChunkGenerator.PreparedChunkBuildings prepared, int[] terrainSurfaces, int[] waterSurfaces, boolean[] waterFlags, int minY, int maxY
   ) {
      if (prepared != null && !prepared.isEmpty()) {
         for (int index = 0; index < CHUNK_AREA; index++) {
            int flattenedSurface = prepared.flattenedTerrainSurface(index);
            if (flattenedSurface != Integer.MIN_VALUE) {
               int clampedSurface = Mth.clamp(flattenedSurface, minY, maxY);
               terrainSurfaces[index] = clampedSurface;
               waterSurfaces[index] = clampedSurface;
               waterFlags[index] = false;
            }
         }
      }
   }

   private void applyLandmarkGroundToTerrain(ChunkPos pos, int[] terrainSurfaces, int[] waterSurfaces, boolean[] waterFlags, int minY, int maxY) {
      int chunkMinX = pos.getMinBlockX();
      int chunkMinZ = pos.getMinBlockZ();
      for (LandmarkPlacement landmark : this.landmarks.intersecting(chunkMinX, chunkMinZ, chunkMinX + CHUNK_MASK, chunkMinZ + CHUNK_MASK)) {
         for (int index = 0; index < CHUNK_AREA; index++) {
            int surface = landmark.flattenedSurface(chunkMinX + index % CHUNK_SIDE, chunkMinZ + index / CHUNK_SIDE);
            if (surface != Integer.MIN_VALUE) {
               int clampedSurface = Mth.clamp(surface, minY, maxY);
               terrainSurfaces[index] = clampedSurface;
               waterSurfaces[index] = clampedSurface;
               waterFlags[index] = false;
            }
         }
      }
   }

   private boolean landmarkSuppressesTree(int worldX, int worldZ) {
      for (LandmarkPlacement landmark : this.landmarks.placements()) {
         if (landmark.suppressesTree(worldX, worldZ)) {
            return true;
         }
      }
      return false;
   }

   private void placeLandmarks(WorldGenLevel level, ChunkAccess chunk) {
      int chunkMinX = chunk.getPos().getMinBlockX();
      int chunkMinZ = chunk.getPos().getMinBlockZ();
      List<LandmarkPlacement> landmarks = this.landmarks.intersecting(chunkMinX, chunkMinZ, chunkMinX + CHUNK_MASK, chunkMinZ + CHUNK_MASK);
      if (landmarks.isEmpty()) {
         return;
      }
      int minY = MinecraftVersionCompat.minY(chunk);
      int maxY = minY + chunk.getHeight() - 1;
      int flags = level instanceof ServerLevel ? 2 : 260;
      MutableBlockPos cursor = new MutableBlockPos();
      for (LandmarkPlacement landmark : landmarks) {
         for (int index = 0; index < CHUNK_AREA; index++) {
            int worldX = chunkMinX + index % CHUNK_SIDE;
            int worldZ = chunkMinZ + index / CHUNK_SIDE;
            landmark.columnSpans(worldX, worldZ, minY, maxY, (bottomY, topY, state) -> {
               for (int y = bottomY; y <= topY; y++) {
                  cursor.set(worldX, y, worldZ);
                  level.setBlock(cursor, state, flags);
               }
            });
         }
      }
   }

   private void placePreparedBuildings(WorldGenLevel level, ChunkAccess chunk) {
      long chunkKey = MinecraftVersionCompat.packChunkPos(
         MinecraftVersionCompat.chunkX(chunk.getPos()), MinecraftVersionCompat.chunkZ(chunk.getPos())
      );
      EarthChunkGenerator.PreparedChunkBuildings prepared = this.preparedChunkBuildings.remove(chunkKey);
      this.clearPreparedChunkStateTracking(chunkKey);
      this.placePreparedBuildings(level, chunk, prepared);
   }

   private void placePreparedBuildings(WorldGenLevel level, ChunkAccess chunk, EarthChunkGenerator.PreparedChunkBuildings prepared) {
      if (prepared != null && !prepared.isEmpty()) {
         int minY = MinecraftVersionCompat.minY(chunk);
         int maxY = minY + chunk.getHeight() - 1;
         int chunkMinX = chunk.getPos().getMinBlockX();
         int chunkMinZ = chunk.getPos().getMinBlockZ();
         int flags = level instanceof ServerLevel ? 2 : 260;
         MutableBlockPos cursor = new MutableBlockPos();

         for (EarthChunkGenerator.PreparedBuildingFeature feature : prepared.features()) {
            TellusBuildingMaterials.BuildingMaterialPalette palette = this.paletteForBlueprint(feature.blueprint());

            for (int index : feature.occupiedIndices()) {
               int columnTopY = Mth.clamp(feature.columnTopY(index), minY, maxY);
               if (columnTopY < feature.floorY()) {
                  continue;
               }

               int localX = index % CHUNK_SIDE;
               int localZ = index / CHUNK_SIDE;
               int worldX = chunkMinX + localX;
               int worldZ = chunkMinZ + localZ;
               this.placePreparedBuildingColumn(level, cursor, flags, feature, palette, worldX, worldZ, index, columnTopY, chunkMinX, chunkMinZ, minY, maxY);
            }
         }
         for (EarthChunkGenerator.PreparedBuildingFeature feature : prepared.features()) {
            BuildingBlueprint blueprint = feature.blueprint();
            int x = blueprint.entranceWorldX(), z = blueprint.entranceWorldZ();
            if (!feature.groundContact() || blueprint.entranceWidth() == 0
               || x < chunkMinX || x > chunkMinX + CHUNK_MASK || z < chunkMinZ || z > chunkMinZ + CHUNK_MASK
               || feature.columnTopY(chunkIndex(x - chunkMinX, z - chunkMinZ)) < blueprint.floorY() + 2) continue;
            BuildingEntranceAccess.place(blueprint, minY, maxY,
               position -> level.hasChunk(position.getX() >> 4, position.getZ() >> 4)
                  ? level.getBlockState(position) : Blocks.BARRIER.defaultBlockState(),
               (position, state) -> level.setBlock(position, state, flags));
         }
      }
   }

   private void applyPreparedBuildingTerrainToChunk(WorldGenLevel level, ChunkAccess chunk, EarthChunkGenerator.PreparedChunkBuildings prepared) {
      if (prepared == null || prepared.isEmpty()) {
         return;
      }

      int minY = MinecraftVersionCompat.minY(chunk);
      int maxY = minY + chunk.getHeight() - 1;
      int chunkMinX = chunk.getPos().getMinBlockX();
      int chunkMinZ = chunk.getPos().getMinBlockZ();
      int flags = this.detailApplyFlags(level);
      MutableBlockPos cursor = new MutableBlockPos();
      boolean thinShellTerrain = this.settings.usesTerrainShell();

      for (int index = 0; index < CHUNK_AREA; index++) {
         int flattenedSurface = prepared.flattenedTerrainSurface(index);
         if (flattenedSurface == Integer.MIN_VALUE) {
            continue;
         }

         int targetY = Mth.clamp(flattenedSurface, minY, maxY);
         int localX = index % CHUNK_SIDE;
         int localZ = index / CHUNK_SIDE;
         int worldX = chunkMinX + localX;
         int worldZ = chunkMinZ + localZ;
         int currentY = Mth.clamp(level.getHeight(Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ) - 1, minY - 1, maxY);
         BlockState climateTop = this.resolveClimateBasedBuiltUpTerrainTopBlock(level, cursor, worldX, worldZ, targetY);
         BlockState targetTop = climateTop != null ? climateTop : targetY < this.minY + 64 ? DEEPSLATE_STATE : STONE_STATE;
         if (thinShellTerrain) {
            if (currentY < targetY) {
               for (int y = Math.max(minY, currentY - 1); y < targetY - 1; y++) {
                  cursor.set(worldX, y, worldZ);
                  level.setBlock(cursor, AIR_STATE, flags);
               }
            }

            int clearTop = Math.max(currentY, targetY);
            for (int y = targetY + 1; y <= clearTop; y++) {
               cursor.set(worldX, y, worldZ);
               level.setBlock(cursor, AIR_STATE, flags);
            }

            int supportY = targetY - 1;
            if (supportY >= minY && supportY <= maxY) {
               cursor.set(worldX, supportY, worldZ);
               level.setBlock(cursor, BEDROCK_STATE, flags);
            }

            cursor.set(worldX, targetY, worldZ);
            level.setBlock(cursor, targetTop, flags);
         } else if (currentY < targetY) {
            for (int y = currentY + 1; y <= targetY; y++) {
               cursor.set(worldX, y, worldZ);
               level.setBlock(cursor, y == targetY ? targetTop : y < this.minY + 64 ? DEEPSLATE_STATE : STONE_STATE, flags);
            }
         } else if (currentY > targetY) {
            for (int y = targetY + 1; y <= currentY; y++) {
               cursor.set(worldX, y, worldZ);
               level.setBlock(cursor, AIR_STATE, flags);
            }

            if (climateTop != null) {
               cursor.set(worldX, targetY, worldZ);
               level.setBlock(cursor, climateTop, flags);
            }
         }
      }
   }

   private BlockState resolveClimateBasedBuiltUpTerrainTopBlock(WorldGenLevel level, MutableBlockPos cursor, int worldX, int worldZ, int surfaceY) {
      if (!this.settings.climateBasedBuiltUpTerrain() || this.sampleCoverClass(worldX, worldZ) != ESA_BUILT_UP) {
         return null;
      }

      cursor.set(worldX, surfaceY, worldZ);
      EarthChunkGenerator.SurfacePalette palette = this.selectBaseSurfacePalette(level.getBiome(cursor), worldX, worldZ, surfaceY, ESA_BUILT_UP);
      return palette == null ? null : palette.top();
   }

   private List<RoadFeature> fetchEntranceRoads(OsmBuildingFeature feature, double worldScale, OsmQueryMode queryMode) {
      if (!(worldScale > 0.0) || worldScale > 1.0 || !OSM_ROAD_SOURCE.available()) return List.of();
      // Every chunk of a building must see the same streets when choosing its front door.
      return this.fetchOsmRoadsForAreaDetailed(Mth.floor(feature.minBlockXForScale(worldScale)),
         Mth.floor(feature.minBlockZ(worldScale)), Mth.ceil(feature.maxBlockXForScale(worldScale)),
         Mth.ceil(feature.maxBlockZ(worldScale)), 32, queryMode).features();
   }

   private List<OsmBuildingFeature> fetchEntranceNeighbors(OsmBuildingFeature feature, double worldScale, OsmQueryMode queryMode) {
      if (!(worldScale > 0.0) || worldScale > 1.0) return List.of();
      return this.fetchOsmBuildingsForAreaDetailed(Mth.floor(feature.minBlockXForScale(worldScale)),
         Mth.floor(feature.minBlockZ(worldScale)), Mth.ceil(feature.maxBlockXForScale(worldScale)),
         Mth.ceil(feature.maxBlockZ(worldScale)), BuildingEntranceLayout.APPROACH_LENGTH + 2, queryMode).features();
   }

   private BuildingProfile resolveBuildingProfile(OsmBuildingFeature feature, int baseY, Holder<Biome> biome, double worldScale) {
      boolean interiorsEnabled = worldScale == 1.0;
      return TellusBuildingProfiles.resolveProfile(feature, worldScale, biome, interiorsEnabled);
   }

   private Holder<Biome> sampleBuildingBiome(OsmBuildingFeature feature, int baseY, RandomState random) {
      double[] centroid = feature.centroidWorld(this.settings.worldScale());
      int centerX = Mth.floor(centroid[0]);
      int centerZ = Mth.floor(centroid[1]);
      int sampleY = Mth.clamp(baseY, this.minY, this.minY + this.height - 1);
      return WorldgenApi.sampleBiome(this.biomeSource, QuartPos.fromBlock(centerX), QuartPos.fromBlock(sampleY), QuartPos.fromBlock(centerZ), random);
   }

   private Holder<Biome> sampleBuildingBiome(OsmBuildingFeature feature, int baseY, EarthChunkGenerator.ChunkGenerationContext context) {
      double[] centroid = feature.centroidWorld(this.settings.worldScale());
      int centerX = Mth.floor(centroid[0]);
      int centerZ = Mth.floor(centroid[1]);
      int sampleY = Mth.clamp(baseY, this.minY, this.minY + this.height - 1);
      return context.sampleBiome(centerX, centerZ, sampleY);
   }

   private int buildingFloorY(int baseY, OsmBuildingFeature feature) {
      return baseY + this.buildingMinHeightBlocks(feature) + 1;
   }

   private int buildingRoofBaseY(int baseY, OsmBuildingFeature feature, int floorY, BuildingProfile profile) {
      int dataHeight = Math.max(floorY + 1, baseY + this.buildingHeightBlocks(feature));
      int storeyHeight = Math.max(1, profile.floorCount() * profile.storeyHeightBlocks());
      return Math.max(dataHeight, floorY + storeyHeight);
   }

   private EarthChunkGenerator.BuildingBoundaryInfo computeBuildingBoundaryInfo(boolean[] occupied, boolean[] extOccupied, int extSide) {
      int extArea = extOccupied.length;
      int[] extDistance = new int[extArea];
      Arrays.fill(extDistance, Integer.MAX_VALUE);
      ArrayDeque<Integer> queue = new ArrayDeque<>();
      int padding = BUILDING_SLICE_PADDING;

      for (int localZ = 0; localZ < extSide; localZ++) {
         for (int localX = 0; localX < extSide; localX++) {
            int index = extIndex(localX, localZ, extSide);
            if (!extOccupied[index]) {
               continue;
            }

            boolean boundary = localX == 0
               || localX == extSide - 1
               || localZ == 0
               || localZ == extSide - 1
               || !extOccupied[extIndex(localX - 1, localZ, extSide)]
               || !extOccupied[extIndex(localX + 1, localZ, extSide)]
               || !extOccupied[extIndex(localX, localZ - 1, extSide)]
               || !extOccupied[extIndex(localX, localZ + 1, extSide)];
            if (boundary) {
               extDistance[index] = 0;
               queue.add(index);
            }
         }
      }

      while (!queue.isEmpty()) {
         int index = queue.removeFirst();
         int localX = index % extSide;
         int localZ = index / extSide;
         int nextDistance = extDistance[index] + 1;
         if (localX > 0) {
            this.propagateBoundaryDistance(extOccupied, extDistance, queue, extIndex(localX - 1, localZ, extSide), nextDistance);
         }

         if (localX + 1 < extSide) {
            this.propagateBoundaryDistance(extOccupied, extDistance, queue, extIndex(localX + 1, localZ, extSide), nextDistance);
         }

         if (localZ > 0) {
            this.propagateBoundaryDistance(extOccupied, extDistance, queue, extIndex(localX, localZ - 1, extSide), nextDistance);
         }

         if (localZ + 1 < extSide) {
            this.propagateBoundaryDistance(extOccupied, extDistance, queue, extIndex(localX, localZ + 1, extSide), nextDistance);
         }
      }

      boolean[] boundary = new boolean[CHUNK_AREA];
      int[] boundaryDistance = new int[CHUNK_AREA];
      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         for (int localX = 0; localX < CHUNK_SIDE; localX++) {
            int index = chunkIndex(localX, localZ);
            if (!occupied[index]) {
               continue;
            }

            int extIndex = extIndex(localX + padding, localZ + padding, extSide);
            int distance = extDistance[extIndex];
            boundary[index] = distance == 0;
            boundaryDistance[index] = distance == Integer.MAX_VALUE ? 0 : distance;
         }
      }

      return new EarthChunkGenerator.BuildingBoundaryInfo(boundary, boundaryDistance);
   }

   private void propagateBoundaryDistance(boolean[] occupied, int[] distance, ArrayDeque<Integer> queue, int index, int nextDistance) {
      if (occupied[index] && nextDistance < distance[index]) {
         distance[index] = nextDistance;
         queue.add(index);
      }
   }

   private boolean usesParapet(BuildingProfile.RoofProfile roofProfile) {
      return roofProfile == BuildingProfile.RoofProfile.FLAT
         || roofProfile == BuildingProfile.RoofProfile.FLAT_PARAPET
         || roofProfile == BuildingProfile.RoofProfile.FLAT_CROWN
         || roofProfile == BuildingProfile.RoofProfile.FLAT_SKYLIGHT;
   }

   private boolean isPitchedRoof(BuildingProfile.RoofProfile roofProfile) {
      return roofProfile == BuildingProfile.RoofProfile.GABLED_X
         || roofProfile == BuildingProfile.RoofProfile.GABLED_Z
         || roofProfile == BuildingProfile.RoofProfile.HIPPED
         || roofProfile == BuildingProfile.RoofProfile.PYRAMIDAL
         || roofProfile == BuildingProfile.RoofProfile.SKILLION;
   }

   private BlockState roofStateForColumn(
      EarthChunkGenerator.PreparedBuildingFeature feature,
      TellusBuildingMaterials.BuildingMaterialPalette palette,
      int worldX,
      int worldZ,
      int index,
      int boundaryDistance,
      int columnTopY,
      int chunkMinX,
      int chunkMinZ,
      boolean roofEdge,
      int y
   ) {
      BuildingBlueprint blueprint = feature.blueprint();
      if (y != columnTopY) {
         if (blueprint.profile().category() == BuildingProfile.BuildingCategory.HOUSE
            && this.isPitchedRoof(blueprint.profile().roofProfile())) {
            if (y == blueprint.roofBaseY(boundaryDistance)) {
               return palette.floor();
            }
            // Masonry gable ends enclose a hollow attic below the roof covering.
            return roofEdge ? palette.wall() : AIR_STATE;
         }
         return palette.roof();
      }
      if (!this.isPitchedRoof(blueprint.profile().roofProfile())) {
         return roofEdge ? palette.trim() : palette.roof();
      }

      Direction lowerDirection = this.lowerRoofNeighborDirection(feature, blueprint, worldX, worldZ, index, boundaryDistance, columnTopY, chunkMinX, chunkMinZ);
      return lowerDirection == null ? palette.roof() : this.arnisRoofStairState(palette, lowerDirection.getOpposite());
   }

   private Direction lowerRoofNeighborDirection(
      EarthChunkGenerator.PreparedBuildingFeature feature,
      BuildingBlueprint blueprint,
      int worldX,
      int worldZ,
      int index,
      int boundaryDistance,
      int columnTopY,
      int chunkMinX,
      int chunkMinZ
   ) {
      Direction bestDirection = null;
      int bestDrop = 0;
      int localX = worldX - chunkMinX;
      int localZ = worldZ - chunkMinZ;
      for (Direction direction : Direction.Plane.HORIZONTAL) {
         int neighborX = localX + direction.getStepX();
         int neighborZ = localZ + direction.getStepZ();
         if (neighborX < 0 || neighborX >= CHUNK_SIDE || neighborZ < 0 || neighborZ >= CHUNK_SIDE) {
            continue;
         }

         int neighborIndex = chunkIndex(neighborX, neighborZ);
         if (!feature.occupied(neighborIndex)) {
            if (bestDirection == null) {
               bestDirection = direction;
            }
            continue;
         }

         int neighborTopY = feature.columnTopY(neighborIndex);
         int drop = columnTopY - neighborTopY;
         if (drop > bestDrop) {
            bestDrop = drop;
            bestDirection = direction;
         }
      }

      if (bestDrop > 0) {
         return bestDirection;
      }
      if (feature.boundary(index)) {
         return this.roofSlopeLowerDirection(blueprint, worldX, worldZ, boundaryDistance);
      }
      return null;
   }

   private Direction roofSlopeLowerDirection(BuildingBlueprint blueprint, int worldX, int worldZ, int boundaryDistance) {
      int minX = blueprint.minWorldX() + blueprint.setbackForFloor(blueprint.highestActiveFloor(boundaryDistance));
      int maxX = blueprint.maxWorldX() - blueprint.setbackForFloor(blueprint.highestActiveFloor(boundaryDistance));
      int minZ = blueprint.minWorldZ() + blueprint.setbackForFloor(blueprint.highestActiveFloor(boundaryDistance));
      int maxZ = blueprint.maxWorldZ() - blueprint.setbackForFloor(blueprint.highestActiveFloor(boundaryDistance));
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

   private TellusBuildingMaterials.BuildingMaterialPalette paletteForBlueprint(BuildingBlueprint blueprint) {
      return TellusBuildingMaterials.resolvePalette(blueprint);
   }

   private void placePreparedBuildingColumn(
      WorldGenLevel level,
      MutableBlockPos cursor,
      int flags,
      EarthChunkGenerator.PreparedBuildingFeature feature,
      TellusBuildingMaterials.BuildingMaterialPalette palette,
      int worldX,
      int worldZ,
      int index,
      int columnTopY,
      int chunkMinX,
      int chunkMinZ,
      int minY,
      int maxY
   ) {
      BuildingBlueprint blueprint = feature.blueprint();
      if (!blueprint.interiorsEnabled()) {
         this.placeExteriorOnlyBuildingColumn(level, cursor, flags, feature, palette, worldX, worldZ, index, columnTopY, chunkMinX, chunkMinZ, minY, maxY);
         return;
      }

      TellusBuildingInteriors interior = feature.interior();
      int boundaryDistance = feature.boundaryDistance(index);
      int highestFloor = blueprint.highestActiveFloor(boundaryDistance);
      for (int floorIndex = 0; floorIndex <= highestFloor; floorIndex++) {
         if (boundaryDistance < blueprint.setbackForFloor(floorIndex)) {
            continue;
         }

         int floorBottom = Mth.clamp(blueprint.floorBottomY(floorIndex), minY, maxY);
         int floorTop = Mth.clamp(Math.min(columnTopY, blueprint.floorTopY(floorIndex)), minY, maxY);
         if (floorTop < floorBottom) {
            continue;
         }

         for (int y = floorBottom; y <= floorTop; y++) {
            BlockState state = interior.blockAt(palette, boundaryDistance, worldX, worldZ, floorIndex, y);
            this.setBuildingBlock(level, cursor, worldX, y, worldZ, state, flags, minY, maxY);
         }
      }

      int roofBaseY = Mth.clamp(blueprint.roofBaseY(boundaryDistance), minY, maxY);
      boolean roofEdge = blueprint.isFacadeCell(boundaryDistance, highestFloor);
      for (int y = roofBaseY; y <= columnTopY; y++) {
         BlockState state = this.roofStateForColumn(feature, palette, worldX, worldZ, index, boundaryDistance, columnTopY, chunkMinX, chunkMinZ, roofEdge, y);
         this.setBuildingBlock(level, cursor, worldX, y, worldZ, state, flags, minY, maxY);
      }

      if (blueprint.isEntranceCell(worldX, worldZ)) {
         this.placeEntranceDoor(level, cursor, flags, blueprint, feature, worldX, worldZ, minY, maxY);
      }
      this.placeBuildingExteriorDetails(level, cursor, flags, feature, palette, worldX, worldZ, index, columnTopY, chunkMinX, chunkMinZ, minY, maxY);
   }

   private void placeExteriorOnlyBuildingColumn(
      WorldGenLevel level,
      MutableBlockPos cursor,
      int flags,
      EarthChunkGenerator.PreparedBuildingFeature feature,
      TellusBuildingMaterials.BuildingMaterialPalette palette,
      int worldX,
      int worldZ,
      int index,
      int columnTopY,
      int chunkMinX,
      int chunkMinZ,
      int minY,
      int maxY
   ) {
      BuildingBlueprint blueprint = feature.blueprint();
      int floorY = Mth.clamp(blueprint.floorY(), minY, maxY);
      int boundaryDistance = feature.boundaryDistance(index);
      int highestFloor = blueprint.highestActiveFloor(boundaryDistance);
      boolean roofEdge = blueprint.isFacadeCell(boundaryDistance, highestFloor);
      for (int y = floorY; y <= columnTopY; y++) {
         BlockState state;
         if (y == floorY) {
            state = palette.floor();
         } else if (y >= blueprint.roofBaseY(boundaryDistance)) {
            state = this.roofStateForColumn(feature, palette, worldX, worldZ, index, boundaryDistance, columnTopY, chunkMinX, chunkMinZ, roofEdge, y);
         } else {
            int floorIndex = blueprint.floorIndexAtY(y);
            int floorBottom = blueprint.floorBottomY(floorIndex);
            int floorTop = Math.min(columnTopY, blueprint.floorTopY(floorIndex));
            boolean facadeCell = blueprint.isFacadeCell(boundaryDistance, floorIndex);
            state = facadeCell
               ? TellusBuildingFacade.resolveFacadeBlock(blueprint, palette, boundaryDistance, worldX, worldZ, floorIndex, floorBottom, floorTop, y)
               : palette.wall();
         }

         this.setBuildingBlock(level, cursor, worldX, y, worldZ, state, flags, minY, maxY);
      }

      if (blueprint.isEntranceCell(worldX, worldZ)) {
         this.placeEntranceDoor(level, cursor, flags, blueprint, feature, worldX, worldZ, minY, maxY);
      }
      this.placeBuildingExteriorDetails(level, cursor, flags, feature, palette, worldX, worldZ, index, columnTopY, chunkMinX, chunkMinZ, minY, maxY);
   }

   private void placeBuildingExteriorDetails(
      WorldGenLevel level,
      MutableBlockPos cursor,
      int flags,
      EarthChunkGenerator.PreparedBuildingFeature feature,
      TellusBuildingMaterials.BuildingMaterialPalette palette,
      int worldX,
      int worldZ,
      int index,
      int columnTopY,
      int chunkMinX,
      int chunkMinZ,
      int minY,
      int maxY
   ) {
      BuildingBlueprint blueprint = feature.blueprint();
      if (!blueprint.style().detailedExterior(this.settings.worldScale())) {
         return;
      }

      int boundaryDistance = feature.boundaryDistance(index);
      int highestFloor = blueprint.highestActiveFloor(boundaryDistance);
      Direction facing = this.facadeOutwardFacing(feature, index, boundaryDistance, worldX, worldZ, chunkMinX, chunkMinZ);
      if (facing != null) {
         for (int floorIndex = 0; floorIndex <= highestFloor; floorIndex++) {
            if (!blueprint.isFacadeCell(boundaryDistance, floorIndex)) {
               continue;
            }

            int floorBottom = blueprint.floorBottomY(floorIndex);
            int floorTop = Math.min(columnTopY, blueprint.floorTopY(floorIndex));
            if (floorIndex == 0 && blueprint.nearEntranceApproach(worldX, worldZ)) {
               // Adjacent diagonal facade cells must not project trim, glazing or railings into the entry.
               if (!blueprint.isEntranceCell(worldX, worldZ)) continue;
               Direction entranceFacing = blueprint.entranceFacing();
               this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ,
                  worldX + entranceFacing.getStepX(), floorBottom + 3, worldZ + entranceFacing.getStepZ(),
                  this.arnisTopSlab(palette.slab()), minY, maxY);
               continue;
            }
            this.placeArnisFacadeDepth(level, cursor, flags, blueprint, palette, worldX, worldZ, floorIndex, floorBottom, floorTop, facing, chunkMinX, chunkMinZ, minY, maxY);
            ArnisBuildingRules.ResidentialDecoration residentialDecoration = ArnisBuildingRules.residentialWindowDecoration(
               blueprint, boundaryDistance, worldX, worldZ, floorIndex, floorBottom, floorTop
            );
            if (TellusBuildingFacade.shouldPlaceAwning(blueprint, boundaryDistance, worldX, worldZ, floorIndex)) {
               this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, worldX + facing.getStepX(), floorBottom + 3, worldZ + facing.getStepZ(), palette.awning(), minY, maxY);
            }
            if (TellusBuildingFacade.shouldPlaceBalcony(blueprint, boundaryDistance, worldX, worldZ, floorIndex, floorBottom, floorTop)
               || residentialDecoration == ArnisBuildingRules.ResidentialDecoration.BALCONY) {
               this.placeArnisBalcony(level, cursor, flags, blueprint, palette, worldX, worldZ, floorIndex, Math.min(floorTop, floorBottom + 2), facing, chunkMinX, chunkMinZ, minY, maxY);
            } else if (TellusBuildingFacade.shouldPlaceFireEscape(blueprint, boundaryDistance, worldX, worldZ, floorIndex)) {
               int outsideX = worldX + facing.getStepX();
               int outsideZ = worldZ + facing.getStepZ();
               this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, outsideX, floorBottom + 1, outsideZ, palette.railing(), minY, maxY);
               this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, outsideX, floorBottom + 2, outsideZ, palette.railing(), minY, maxY);
            } else if (residentialDecoration == ArnisBuildingRules.ResidentialDecoration.WINDOW_SILL
               || this.shouldPlaceWindowSill(blueprint, boundaryDistance, worldX, worldZ, floorIndex, floorBottom, floorTop)) {
               this.placeArnisWindowSill(level, cursor, flags, blueprint, palette, worldX, worldZ, floorIndex, Math.min(floorTop, floorBottom + 2), facing, chunkMinX, chunkMinZ, minY, maxY);
            }
            if (ArnisBuildingRules.shouldPlaceResidentialShutter(blueprint, boundaryDistance, worldX, worldZ, floorIndex, floorBottom, floorTop)) {
               this.placeArnisResidentialShutters(level, cursor, flags, blueprint, worldX, worldZ, floorBottom, floorTop, facing, chunkMinX, chunkMinZ, minY, maxY);
            }
         }
      }

      int roofBaseY = blueprint.roofBaseY(boundaryDistance);
      if (facing != null && columnTopY >= roofBaseY && blueprint.isFacadeCell(boundaryDistance, highestFloor)) {
         this.placeArnisRoofline(level, cursor, flags, blueprint, palette, worldX, worldZ, columnTopY, facing, chunkMinX, chunkMinZ, minY, maxY);
      }

      if (columnTopY >= roofBaseY && TellusBuildingFacade.shouldPlaceChimney(blueprint, boundaryDistance, worldX, worldZ)) {
         this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, worldX, columnTopY + 1, worldZ, Blocks.BRICKS.defaultBlockState(), minY, maxY);
         this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, worldX, columnTopY + 2, worldZ, Blocks.BRICKS.defaultBlockState(), minY, maxY);
      }

      if (columnTopY >= roofBaseY) {
         this.placeArnisRooftopEquipment(level, cursor, flags, feature, blueprint, boundaryDistance, worldX, worldZ, columnTopY, chunkMinX, chunkMinZ, minY, maxY);
      }
   }

   private void placeArnisRoofline(
      WorldGenLevel level,
      MutableBlockPos cursor,
      int flags,
      BuildingBlueprint blueprint,
      TellusBuildingMaterials.BuildingMaterialPalette palette,
      int worldX,
      int worldZ,
      int columnTopY,
      Direction facing,
      int chunkMinX,
      int chunkMinZ,
      int minY,
      int maxY
   ) {
      int outsideX = worldX + facing.getStepX();
      int outsideZ = worldZ + facing.getStepZ();
      if (this.usesParapet(blueprint.profile().roofProfile())) {
         switch (ArnisBuildingRules.flatRoofEdgeVariation(blueprint)) {
            case PARAPET -> {
               this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, outsideX, columnTopY, outsideZ, palette.trim(), minY, maxY);
               this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, outsideX, columnTopY + 1, outsideZ, palette.slab(), minY, maxY);
            }
            case WALL_CAP -> this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, outsideX, columnTopY + 1, outsideZ, palette.wall(), minY, maxY);
            case SLAB_CAP -> this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, outsideX, columnTopY + 1, outsideZ, this.arnisTopSlab(palette.slab()), minY, maxY);
            case ACCENT_ROW -> this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, outsideX, columnTopY + 1, outsideZ, palette.accent(), minY, maxY);
            case NONE -> {
            }
         }
      } else {
         this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, outsideX, columnTopY, outsideZ, this.arnisRoofStairState(palette, facing.getOpposite()), minY, maxY);
      }
   }

   private void placeArnisBalcony(
      WorldGenLevel level,
      MutableBlockPos cursor,
      int flags,
      BuildingBlueprint blueprint,
      TellusBuildingMaterials.BuildingMaterialPalette palette,
      int worldX,
      int worldZ,
      int floorIndex,
      int balconyY,
      Direction facing,
      int chunkMinX,
      int chunkMinZ,
      int minY,
      int maxY
   ) {
      Direction tangent = facing.getClockWise();
      BlockState floor = this.arnisTopSlab(Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
      BlockState frontFence = this.arnisTrapdoorState(blueprint, facing);
      BlockState leftFence = this.arnisTrapdoorState(blueprint, tangent.getOpposite());
      BlockState rightFence = this.arnisTrapdoorState(blueprint, tangent);

      for (int offset = -1; offset <= 1; offset++) {
         int baseX = worldX + tangent.getStepX() * offset;
         int baseZ = worldZ + tangent.getStepZ() * offset;
         for (int depth = 1; depth <= 2; depth++) {
            this.setDecorationBlock(
               level,
               cursor,
               flags,
               chunkMinX,
               chunkMinZ,
               baseX + facing.getStepX() * depth,
               balconyY,
               baseZ + facing.getStepZ() * depth,
               floor,
               minY,
               maxY
            );
         }
      }

      for (int offset = -1; offset <= 1; offset++) {
         this.setDecorationBlock(
            level,
            cursor,
            flags,
            chunkMinX,
            chunkMinZ,
            worldX + tangent.getStepX() * offset + facing.getStepX() * 3,
            balconyY + 1,
            worldZ + tangent.getStepZ() * offset + facing.getStepZ() * 3,
            frontFence,
            minY,
            maxY
         );
      }

      for (int depth = 1; depth <= 2; depth++) {
         this.setDecorationBlock(
            level,
            cursor,
            flags,
            chunkMinX,
            chunkMinZ,
            worldX - tangent.getStepX() * 2 + facing.getStepX() * depth,
            balconyY + 1,
            worldZ - tangent.getStepZ() * 2 + facing.getStepZ() * depth,
            leftFence,
            minY,
            maxY
         );
         this.setDecorationBlock(
            level,
            cursor,
            flags,
            chunkMinX,
            chunkMinZ,
            worldX + tangent.getStepX() * 2 + facing.getStepX() * depth,
            balconyY + 1,
            worldZ + tangent.getStepZ() * 2 + facing.getStepZ() * depth,
            rightFence,
            minY,
            maxY
         );
      }

      int furnitureRoll = arnisDetailRoll(blueprint.blueprintSeed(), worldX + floorIndex * 11, worldZ + floorIndex * 17, 100);
      int side = arnisDetailRoll(blueprint.blueprintSeed(), worldX + floorIndex * 19, worldZ + floorIndex * 23, 2) == 0 ? -1 : 1;
      int furnitureX = worldX + tangent.getStepX() * side + facing.getStepX();
      int furnitureZ = worldZ + tangent.getStepZ() * side + facing.getStepZ();
      if (furnitureRoll < 30) {
         this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, furnitureX, balconyY + 1, furnitureZ, Blocks.CAULDRON.defaultBlockState(), minY, maxY);
         this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, furnitureX, balconyY + 2, furnitureZ, this.persistentLeavesState(), minY, maxY);
      } else if (furnitureRoll < 55) {
         this.setDecorationBlock(
            level,
            cursor,
            flags,
            chunkMinX,
            chunkMinZ,
            furnitureX,
            balconyY + 1,
            furnitureZ,
            Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing.getOpposite()),
            minY,
            maxY
         );
      }
   }

   private void placeArnisWindowSill(
      WorldGenLevel level,
      MutableBlockPos cursor,
      int flags,
      BuildingBlueprint blueprint,
      TellusBuildingMaterials.BuildingMaterialPalette palette,
      int worldX,
      int worldZ,
      int floorIndex,
      int sillY,
      Direction facing,
      int chunkMinX,
      int chunkMinZ,
      int minY,
      int maxY
   ) {
      int outsideX = worldX + facing.getStepX();
      int outsideZ = worldZ + facing.getStepZ();
      this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, outsideX, sillY, outsideZ, this.arnisSillState(blueprint), minY, maxY);
      if (ArnisBuildingRules.shouldPlaceWindowBoxPlant(blueprint, worldX, worldZ, floorIndex)) {
         this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, outsideX, sillY + 1, outsideZ, this.arnisPottedPlantState(blueprint, worldX, worldZ, floorIndex), minY, maxY);
      }
   }

   private void placeArnisResidentialShutters(
      WorldGenLevel level,
      MutableBlockPos cursor,
      int flags,
      BuildingBlueprint blueprint,
      int worldX,
      int worldZ,
      int floorBottom,
      int floorTop,
      Direction facing,
      int chunkMinX,
      int chunkMinZ,
      int minY,
      int maxY
   ) {
      int outsideX = worldX + facing.getStepX();
      int outsideZ = worldZ + facing.getStepZ();
      BlockState shutter = this.arnisTrapdoorState(blueprint, facing);
      for (int y = floorBottom + 1; y <= floorTop; y++) {
         if (y > blueprint.floorY() + 1 && ArnisBuildingRules.floorRow(y, blueprint.floorY()) != 0) {
            this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, outsideX, y, outsideZ, shutter, minY, maxY);
         }
      }
   }

   private void placeArnisRooftopEquipment(
      WorldGenLevel level,
      MutableBlockPos cursor,
      int flags,
      EarthChunkGenerator.PreparedBuildingFeature feature,
      BuildingBlueprint blueprint,
      int boundaryDistance,
      int worldX,
      int worldZ,
      int columnTopY,
      int chunkMinX,
      int chunkMinZ,
      int minY,
      int maxY
   ) {
      ArnisBuildingRules.RooftopEquipment equipment = ArnisBuildingRules.rooftopEquipmentAt(blueprint, boundaryDistance, worldX, worldZ);
      if (equipment == ArnisBuildingRules.RooftopEquipment.NONE) {
         return;
      }
      int equipmentY = columnTopY + 1;
      switch (equipment) {
         case HVAC -> {
            this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, worldX, equipmentY, worldZ, Blocks.IRON_BLOCK.defaultBlockState(), minY, maxY);
            this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, worldX, equipmentY + 1, worldZ, Blocks.SMOOTH_STONE_SLAB.defaultBlockState(), minY, maxY);
         }
         case SOLAR_PANEL -> this.placeArnisSolarPanels(level, cursor, flags, feature, blueprint, worldX, worldZ, columnTopY, equipmentY, chunkMinX, chunkMinZ, minY, maxY);
         case ANTENNA -> {
            this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, worldX, equipmentY, worldZ, Blocks.IRON_BARS.defaultBlockState(), minY, maxY);
            this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, worldX, equipmentY + 1, worldZ, Blocks.IRON_BARS.defaultBlockState(), minY, maxY);
            this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, worldX, equipmentY + 2, worldZ, TellusBlockReferences.lightningRodState(), minY, maxY);
         }
         case WATER_TANK -> {
            this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, worldX, equipmentY, worldZ, Blocks.BARREL.defaultBlockState(), minY, maxY);
            this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, worldX, equipmentY + 1, worldZ, Blocks.CAULDRON.defaultBlockState(), minY, maxY);
         }
         case VENT_STACK -> {
            int stackHeight = 2 + arnisDetailRoll(blueprint.blueprintSeed(), worldX + 37, worldZ + 53, 2);
            for (int dy = 0; dy < stackHeight; dy++) {
               this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, worldX, equipmentY + dy, worldZ, Blocks.COBBLESTONE_WALL.defaultBlockState(), minY, maxY);
            }
         }
         case ROOF_ACCESS -> {
            for (int dx = 0; dx <= 1; dx++) {
               for (int dz = 0; dz <= 1; dz++) {
                  int x = worldX + dx;
                  int z = worldZ + dz;
                  this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, x, equipmentY, z, Blocks.STONE_BRICKS.defaultBlockState(), minY, maxY);
                  this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, x, equipmentY + 1, z, Blocks.STONE_BRICKS.defaultBlockState(), minY, maxY);
                  this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, x, equipmentY + 2, z, Blocks.STONE_BRICK_SLAB.defaultBlockState(), minY, maxY);
               }
            }
         }
         case NONE -> {
         }
      }
   }

   private void placeArnisSolarPanels(
      WorldGenLevel level,
      MutableBlockPos cursor,
      int flags,
      EarthChunkGenerator.PreparedBuildingFeature feature,
      BuildingBlueprint blueprint,
      int worldX,
      int worldZ,
      int columnTopY,
      int equipmentY,
      int chunkMinX,
      int chunkMinZ,
      int minY,
      int maxY
   ) {
      boolean alongX = arnisDetailRoll(blueprint.blueprintSeed(), worldX + 71, worldZ + 73, 2) == 0;
      int largeWidth = alongX ? 11 : 9;
      int largeDepth = alongX ? 9 : 11;
      int singleWidth = alongX ? 5 : 4;
      int singleDepth = alongX ? 4 : 5;
      if (this.canPlaceArnisSolarField(feature, worldX, worldZ, largeWidth, largeDepth, columnTopY, chunkMinX, chunkMinZ)) {
         int[][] fields = alongX ? new int[][]{{0, 0}, {6, 0}, {0, 5}, {6, 5}} : new int[][]{{0, 0}, {5, 0}, {0, 6}, {5, 6}};
         int fieldWidth = alongX ? 5 : 4;
         int fieldDepth = alongX ? 4 : 5;
         for (int[] field : fields) {
            this.placeArnisSolarField(level, cursor, flags, worldX + field[0], worldZ + field[1], fieldWidth, fieldDepth, equipmentY, chunkMinX, chunkMinZ, minY, maxY);
         }
      } else if (this.canPlaceArnisSolarField(feature, worldX, worldZ, singleWidth, singleDepth, columnTopY, chunkMinX, chunkMinZ)) {
         this.placeArnisSolarField(level, cursor, flags, worldX, worldZ, singleWidth, singleDepth, equipmentY, chunkMinX, chunkMinZ, minY, maxY);
      } else if (this.isArnisRoofEquipmentCell(feature, worldX, worldZ, columnTopY, chunkMinX, chunkMinZ)) {
         this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, worldX, equipmentY, worldZ, Blocks.DAYLIGHT_DETECTOR.defaultBlockState(), minY, maxY);
      }
   }

   private void placeArnisSolarField(
      WorldGenLevel level,
      MutableBlockPos cursor,
      int flags,
      int worldX,
      int worldZ,
      int width,
      int depth,
      int equipmentY,
      int chunkMinX,
      int chunkMinZ,
      int minY,
      int maxY
   ) {
      for (int dx = 0; dx < width; dx++) {
         for (int dz = 0; dz < depth; dz++) {
            this.setDecorationBlock(
               level,
               cursor,
               flags,
               chunkMinX,
               chunkMinZ,
               worldX + dx,
               equipmentY,
               worldZ + dz,
               Blocks.DAYLIGHT_DETECTOR.defaultBlockState(),
               minY,
               maxY
            );
         }
      }
   }

   private boolean canPlaceArnisSolarField(
      EarthChunkGenerator.PreparedBuildingFeature feature,
      int worldX,
      int worldZ,
      int width,
      int depth,
      int columnTopY,
      int chunkMinX,
      int chunkMinZ
   ) {
      for (int dx = 0; dx < width; dx++) {
         for (int dz = 0; dz < depth; dz++) {
            if (!this.isArnisRoofEquipmentCell(feature, worldX + dx, worldZ + dz, columnTopY, chunkMinX, chunkMinZ)) {
               return false;
            }
         }
      }
      return true;
   }

   private boolean isArnisRoofEquipmentCell(
      EarthChunkGenerator.PreparedBuildingFeature feature, int worldX, int worldZ, int columnTopY, int chunkMinX, int chunkMinZ
   ) {
      int localX = worldX - chunkMinX;
      int localZ = worldZ - chunkMinZ;
      if (localX < 0 || localX >= CHUNK_SIDE || localZ < 0 || localZ >= CHUNK_SIDE) {
         return false;
      }
      int index = chunkIndex(localX, localZ);
      if (!feature.occupied(index) || feature.boundaryDistance(index) < 2 || feature.columnTopY(index) != columnTopY) {
         return false;
      }
      return feature.blueprint().roofBaseY(feature.boundaryDistance(index)) <= columnTopY;
   }

   private BlockState arnisSillState(BuildingBlueprint blueprint) {
      BlockState state = switch (arnisDetailRoll(blueprint.blueprintSeed(), 31, 0, 5)) {
         case 0 -> Blocks.QUARTZ_SLAB.defaultBlockState();
         case 1 -> Blocks.STONE_BRICK_SLAB.defaultBlockState();
         case 2 -> Blocks.MUD_BRICK_SLAB.defaultBlockState();
         case 3 -> Blocks.OAK_SLAB.defaultBlockState();
         default -> Blocks.BRICK_SLAB.defaultBlockState();
      };
      return this.arnisTopSlab(state);
   }

   private BlockState arnisTopSlab(BlockState state) {
      return state.hasProperty(BlockStateProperties.SLAB_TYPE) ? state.setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP) : state;
   }

   private BlockState arnisRoofStairState(TellusBuildingMaterials.BuildingMaterialPalette palette, Direction facing) {
      BlockState roof = palette.roof();
      BlockState stair = Blocks.STONE_BRICK_STAIRS.defaultBlockState();
      if (roof.getBlock() == Blocks.BRICKS) {
         stair = Blocks.BRICK_STAIRS.defaultBlockState();
      } else if (roof.getBlock() == Blocks.DEEPSLATE_TILES) {
         stair = Blocks.DEEPSLATE_TILE_STAIRS.defaultBlockState();
      } else if (roof.getBlock() == Blocks.DEEPSLATE_BRICKS) {
         stair = Blocks.DEEPSLATE_BRICK_STAIRS.defaultBlockState();
      } else if (roof.getBlock() == Blocks.STONE_BRICKS) {
         stair = Blocks.STONE_BRICK_STAIRS.defaultBlockState();
      } else if (roof.getBlock() == Blocks.SANDSTONE || roof.getBlock() == Blocks.SMOOTH_SANDSTONE || roof.getBlock() == Blocks.CUT_SANDSTONE) {
         stair = Blocks.SANDSTONE_STAIRS.defaultBlockState();
      } else if (roof.getBlock() == Blocks.DARK_OAK_PLANKS) {
         stair = Blocks.DARK_OAK_STAIRS.defaultBlockState();
      } else if (roof.getBlock() == Blocks.OAK_PLANKS) {
         stair = Blocks.OAK_STAIRS.defaultBlockState();
      } else if (roof.getBlock() == Blocks.BLACKSTONE) {
         stair = Blocks.BLACKSTONE_STAIRS.defaultBlockState();
      } else if (roof.getBlock() == Blocks.POLISHED_BLACKSTONE_BRICKS) {
         stair = Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS.defaultBlockState();
      } else if (roof.getBlock() == Blocks.QUARTZ_BLOCK || roof.getBlock() == Blocks.SMOOTH_QUARTZ) {
         stair = Blocks.QUARTZ_STAIRS.defaultBlockState();
      } else if (roof.getBlock() == Blocks.COBBLESTONE) {
         stair = Blocks.COBBLESTONE_STAIRS.defaultBlockState();
      } else if (roof.getBlock() == Blocks.MOSSY_COBBLESTONE) {
         stair = Blocks.MOSSY_COBBLESTONE_STAIRS.defaultBlockState();
      } else if (roof.getBlock() == Blocks.MOSSY_STONE_BRICKS) {
         stair = Blocks.MOSSY_STONE_BRICK_STAIRS.defaultBlockState();
      } else if (roof.getBlock() == Blocks.MUD_BRICKS) {
         stair = Blocks.MUD_BRICK_STAIRS.defaultBlockState();
      } else if (roof.getBlock() == Blocks.NETHER_BRICKS) {
         stair = Blocks.NETHER_BRICK_STAIRS.defaultBlockState();
      } else if (roof.getBlock() == Blocks.RED_NETHER_BRICKS) {
         stair = Blocks.RED_NETHER_BRICK_STAIRS.defaultBlockState();
      } else if (roof.getBlock() == Blocks.POLISHED_DEEPSLATE) {
         stair = Blocks.POLISHED_DEEPSLATE_STAIRS.defaultBlockState();
      } else if (roof.getBlock() == Blocks.ANDESITE) {
         stair = Blocks.ANDESITE_STAIRS.defaultBlockState();
      } else if (roof.getBlock() == Blocks.POLISHED_ANDESITE) {
         stair = Blocks.POLISHED_ANDESITE_STAIRS.defaultBlockState();
      } else if (roof.getBlock() == Blocks.GRANITE) {
         stair = Blocks.GRANITE_STAIRS.defaultBlockState();
      } else if (roof.getBlock() == Blocks.POLISHED_GRANITE) {
         stair = Blocks.POLISHED_GRANITE_STAIRS.defaultBlockState();
      } else if (roof.getBlock() == Blocks.DIORITE) {
         stair = Blocks.DIORITE_STAIRS.defaultBlockState();
      } else if (roof.getBlock() == Blocks.POLISHED_DIORITE) {
         stair = Blocks.POLISHED_DIORITE_STAIRS.defaultBlockState();
      } else if (roof.getBlock() == Blocks.END_STONE_BRICKS) {
         stair = Blocks.END_STONE_BRICK_STAIRS.defaultBlockState();
      } else {
         return roof;
      }
      return stair.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? stair.setValue(BlockStateProperties.HORIZONTAL_FACING, facing) : stair;
   }

   private BlockState arnisPottedPlantState(BuildingBlueprint blueprint, int worldX, int worldZ, int floorIndex) {
      return switch (arnisDetailRoll(blueprint.blueprintSeed(), worldX + floorIndex * 29, worldZ + floorIndex * 31, 4)) {
         case 0 -> Blocks.POTTED_POPPY.defaultBlockState();
         case 1 -> Blocks.POTTED_RED_TULIP.defaultBlockState();
         case 2 -> Blocks.POTTED_DANDELION.defaultBlockState();
         default -> Blocks.POTTED_BLUE_ORCHID.defaultBlockState();
      };
   }

   private BlockState arnisTrapdoorState(BuildingBlueprint blueprint, Direction facing) {
      BlockState state = switch (arnisDetailRoll(blueprint.blueprintSeed(), 47, 0, 4)) {
         case 0 -> Blocks.OAK_TRAPDOOR.defaultBlockState();
         case 1 -> Blocks.DARK_OAK_TRAPDOOR.defaultBlockState();
         case 2 -> Blocks.SPRUCE_TRAPDOOR.defaultBlockState();
         default -> Blocks.BIRCH_TRAPDOOR.defaultBlockState();
      };
      if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
         state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
      }
      if (state.hasProperty(BlockStateProperties.OPEN)) {
         state = state.setValue(BlockStateProperties.OPEN, Boolean.TRUE);
      }
      if (state.hasProperty(BlockStateProperties.HALF)) {
         state = state.setValue(BlockStateProperties.HALF, Half.TOP);
      }
      return state;
   }

   private BlockState persistentLeavesState() {
      BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState();
      return leaves.hasProperty(BlockStateProperties.PERSISTENT) ? leaves.setValue(BlockStateProperties.PERSISTENT, Boolean.TRUE) : leaves;
   }

   private static int arnisDetailRoll(long seed, int x, int z, int modulus) {
      long mixed = seed ^ (long)x * 341873128712L ^ (long)z * 132897987541L;
      mixed ^= mixed >>> 33;
      mixed *= -49064778989728563L;
      mixed ^= mixed >>> 33;
      mixed *= -4265267296055464877L;
      mixed ^= mixed >>> 33;
      return Math.floorMod((int)(mixed ^ mixed >>> 32), Math.max(1, modulus));
   }

   private void placeArnisFacadeDepth(
      WorldGenLevel level,
      MutableBlockPos cursor,
      int flags,
      BuildingBlueprint blueprint,
      TellusBuildingMaterials.BuildingMaterialPalette palette,
      int worldX,
      int worldZ,
      int floorIndex,
      int floorBottom,
      int floorTop,
      Direction facing,
      int chunkMinX,
      int chunkMinZ,
      int minY,
      int maxY
   ) {
      if (TellusBuildingArchitecture.redesigned(blueprint)) {
         int outsideX = worldX + facing.getStepX();
         int outsideZ = worldZ + facing.getStepZ();
         int edge = TellusBuildingArchitecture.edgeDistance(blueprint, worldX, worldZ, floorIndex);
         if (blueprint.profile().category() == BuildingProfile.BuildingCategory.HOUSE) {
            if (floorIndex == 0) {
               this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, outsideX, floorBottom, outsideZ, palette.trim(), minY, maxY);
            }
            if (TellusBuildingArchitecture.window(blueprint, blueprint.setbackForFloor(floorIndex), worldX, worldZ, floorIndex, floorBottom + 2)) {
               this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, outsideX, floorBottom + 1, outsideZ,
                  this.arnisTopSlab(palette.slab()), minY, maxY);
            }
         } else if (edge % (floorIndex == 0 ? 5 : 4) == 0) {
            this.placeVerticalDecorationColumn(level, cursor, flags, chunkMinX, chunkMinZ, outsideX, outsideZ,
               floorBottom, floorTop, palette.trim(), palette.trim(), minY, maxY);
         }
         return;
      }
      BuildingStyle.WallDepthStyle depthStyle = blueprint.style().wallDepthStyle();
      if (depthStyle == BuildingStyle.WallDepthStyle.NONE || floorTop <= floorBottom) {
         return;
      }

      int bay = ArnisBuildingRules.facadeBay(blueprint, worldX, worldZ);
      int outsideX = worldX + facing.getStepX();
      int outsideZ = worldZ + facing.getStepZ();
      boolean corner = this.isBlueprintCorner(blueprint, floorIndex, worldX, worldZ);
      switch (depthStyle) {
         case SUBTLE_PILASTERS -> {
            if (bay == 3) {
               this.placeVerticalDecorationColumn(level, cursor, flags, chunkMinX, chunkMinZ, outsideX, outsideZ, floorBottom + 1, floorTop, palette.wall(), palette.accent(), minY, maxY);
            }
         }
         case MODERN_PILLARS -> {
            if (bay == 3 || bay == 5) {
               this.placeVerticalDecorationColumn(level, cursor, flags, chunkMinX, chunkMinZ, outsideX, outsideZ, floorBottom + 1, floorTop, palette.accent(), palette.accent(), minY, maxY);
            } else if (bay < 3) {
               this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, outsideX, floorBottom + 1, outsideZ, palette.accent(), minY, maxY);
               this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, outsideX, floorTop, outsideZ, palette.trim(), minY, maxY);
            }
         }
         case INSTITUTIONAL_BANDS -> {
            if (bay == 3) {
               this.placeVerticalDecorationColumn(level, cursor, flags, chunkMinX, chunkMinZ, outsideX, outsideZ, floorBottom + 1, floorTop, palette.accent(), palette.accent(), minY, maxY);
            } else if (bay < 3) {
               this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, outsideX, floorBottom + 1, outsideZ, palette.accent(), minY, maxY);
               this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, outsideX, floorTop, outsideZ, palette.trim(), minY, maxY);
            }
         }
         case INDUSTRIAL_BEAMS, GLASS_CURTAIN -> {
            if (corner) {
               this.placeVerticalDecorationColumn(level, cursor, flags, chunkMinX, chunkMinZ, outsideX, outsideZ, floorBottom + 1, floorTop, palette.accent(), palette.accent(), minY, maxY);
            }
         }
         case HISTORIC_ORNATE -> {
            if (bay == 3) {
               this.placeVerticalDecorationColumn(level, cursor, flags, chunkMinX, chunkMinZ, outsideX, outsideZ, floorBottom + 1, floorTop, palette.wall(), palette.accent(), minY, maxY);
            } else {
               this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, outsideX, floorBottom + 1, outsideZ, palette.accent(), minY, maxY);
               if (bay == 0 || bay == 2) {
                  this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, outsideX, Math.max(floorBottom + 2, floorTop - 1), outsideZ, palette.trim(), minY, maxY);
               }
            }
            this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, outsideX, floorTop + 1, outsideZ, palette.trim(), minY, maxY);
         }
         case RELIGIOUS_BUTTRESS -> {
            if (bay == 0) {
               this.placeVerticalDecorationColumn(level, cursor, flags, chunkMinX, chunkMinZ, outsideX, outsideZ, floorBottom + 1, floorTop, palette.wall(), palette.accent(), minY, maxY);
               if (floorIndex <= Math.max(1, blueprint.floorCount() * 3 / 5)) {
                  this.placeVerticalDecorationColumn(
                     level,
                     cursor,
                     flags,
                     chunkMinX,
                     chunkMinZ,
                     outsideX + facing.getStepX(),
                     outsideZ + facing.getStepZ(),
                     floorBottom + 1,
                     Math.max(floorBottom + 1, floorTop - 1),
                     palette.wall(),
                     palette.accent(),
                     minY,
                     maxY
                  );
               }
            } else {
               this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, outsideX, floorTop + 1, outsideZ, palette.trim(), minY, maxY);
            }
         }
         case SKYSCRAPER_FINS -> {
            this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, outsideX, floorBottom + 1, outsideZ, palette.accent(), minY, maxY);
            if (bay == 3) {
               this.placeVerticalDecorationColumn(level, cursor, flags, chunkMinX, chunkMinZ, outsideX, outsideZ, floorBottom + 1, floorTop, palette.accent(), palette.accent(), minY, maxY);
            } else {
               this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, outsideX, floorTop, outsideZ, palette.trim(), minY, maxY);
            }
         }
         case NONE -> {
         }
      }
   }

   private boolean shouldPlaceWindowSill(
      BuildingBlueprint blueprint, int boundaryDistance, int worldX, int worldZ, int floorIndex, int floorBottom, int floorTop
   ) {
      if (TellusBuildingArchitecture.redesigned(blueprint)) {
         return false;
      }
      BuildingStyle style = blueprint.style();
      if (style.facadeFamily() == BuildingStyle.FacadeFamily.GREENHOUSE || style.wallDepthStyle() == BuildingStyle.WallDepthStyle.GLASS_CURTAIN) {
         return false;
      }
      if (floorIndex == 0 && style.groundFloorTreatment() != BuildingStyle.GroundFloorTreatment.RESIDENTIAL) {
         return false;
      }
      return TellusBuildingFacade.shouldPlaceWindow(blueprint, boundaryDistance, worldX, worldZ, floorIndex, floorBottom, floorTop, floorTop);
   }

   private void placeVerticalDecorationColumn(
      WorldGenLevel level,
      MutableBlockPos cursor,
      int flags,
      int chunkMinX,
      int chunkMinZ,
      int worldX,
      int worldZ,
      int bottomY,
      int topY,
      BlockState body,
      BlockState base,
      int minY,
      int maxY
   ) {
      for (int y = bottomY; y <= topY; y++) {
         this.setDecorationBlock(level, cursor, flags, chunkMinX, chunkMinZ, worldX, y, worldZ, y == bottomY ? base : body, minY, maxY);
      }
   }

   private boolean isBlueprintCorner(BuildingBlueprint blueprint, int floorIndex, int worldX, int worldZ) {
      int setback = blueprint.setbackForFloor(floorIndex);
      boolean westEast = worldX == blueprint.minWorldX() + setback || worldX == blueprint.maxWorldX() - setback;
      boolean northSouth = worldZ == blueprint.minWorldZ() + setback || worldZ == blueprint.maxWorldZ() - setback;
      return westEast && northSouth;
   }

   private Direction facadeOutwardFacing(
      EarthChunkGenerator.PreparedBuildingFeature feature, int index, int boundaryDistance, int worldX, int worldZ, int chunkMinX, int chunkMinZ
   ) {
      int localX = worldX - chunkMinX;
      int localZ = worldZ - chunkMinZ;
      for (Direction direction : Direction.Plane.HORIZONTAL) {
         int neighborX = localX + direction.getStepX();
         int neighborZ = localZ + direction.getStepZ();
         if (neighborX < 0 || neighborX >= CHUNK_SIDE || neighborZ < 0 || neighborZ >= CHUNK_SIDE) {
            continue;
         }

         int neighborIndex = chunkIndex(neighborX, neighborZ);
         if (!feature.occupied(neighborIndex) || feature.boundaryDistance(neighborIndex) < boundaryDistance) {
            return direction;
         }
      }

      BuildingBlueprint blueprint = feature.blueprint();
      int floorIndex = blueprint.highestActiveFloor(boundaryDistance);
      int setback = blueprint.setbackForFloor(floorIndex);
      if (worldX <= blueprint.minWorldX() + setback) {
         return Direction.WEST;
      }
      if (worldX >= blueprint.maxWorldX() - setback) {
         return Direction.EAST;
      }
      if (worldZ <= blueprint.minWorldZ() + setback) {
         return Direction.NORTH;
      }
      if (worldZ >= blueprint.maxWorldZ() - setback) {
         return Direction.SOUTH;
      }
      return null;
   }

   private void setDecorationBlock(
      WorldGenLevel level,
      MutableBlockPos cursor,
      int flags,
      int chunkMinX,
      int chunkMinZ,
      int worldX,
      int worldY,
      int worldZ,
      BlockState state,
      int minY,
      int maxY
   ) {
      if (worldY < minY || worldY > maxY || worldX < chunkMinX || worldX >= chunkMinX + CHUNK_SIDE || worldZ < chunkMinZ || worldZ >= chunkMinZ + CHUNK_SIDE) {
         return;
      }

      cursor.set(worldX, worldY, worldZ);
      if (level.isEmptyBlock(cursor)) {
         level.setBlock(cursor, state, flags);
      }
   }

   private void placeEntranceDoor(
      WorldGenLevel level,
      MutableBlockPos cursor,
      int flags,
      BuildingBlueprint blueprint,
      EarthChunkGenerator.PreparedBuildingFeature feature,
      int worldX,
      int worldZ,
      int minY,
      int maxY
   ) {
      if (!this.shouldPlaceBuildingDoors() || !feature.groundContact()) {
         return;
      }

      int localIndex = chunkIndex(worldX & CHUNK_MASK, worldZ & CHUNK_MASK);
      if (feature.columnTopY(localIndex) < blueprint.floorY() + 2) return;
      int lowerY = blueprint.floorY() + 1;
      int upperY = lowerY + 1;
      if (lowerY < minY || upperY > maxY) {
         return;
      }

      BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
         .setValue(BlockStateProperties.HORIZONTAL_FACING, blueprint.entranceFacing())
         .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
      BlockState upper = lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
      this.setBuildingBlock(level, cursor, worldX, lowerY, worldZ, lower, flags, minY, maxY);
      this.setBuildingBlock(level, cursor, worldX, upperY, worldZ, upper, flags, minY, maxY);
   }

   private boolean shouldPlaceBuildingDoors() {
      return this.settings.worldScale() > 0.0 && this.settings.worldScale() <= 1.0;
   }

   private void setBuildingBlock(
      WorldGenLevel level, MutableBlockPos cursor, int worldX, int worldY, int worldZ, BlockState state, int flags, int minY, int maxY
   ) {
      if (state != null && worldY >= minY && worldY <= maxY) {
         cursor.set(worldX, worldY, worldZ);
         level.setBlock(cursor, state, flags);
      }
   }

   private void applyRoadAreaOverlay(
      WorldGenLevel level,
      ChunkAccess chunk,
      MutableBlockPos cursor,
      int chunkMinX,
      int chunkMinZ,
      int chunkMinY,
      int chunkMaxY,
      List<RoadAreaFeature> roadAreas,
      int[] terrainSurfaces,
      int[] waterSurfaces,
      boolean[] waterFlags,
      byte[] chunkRoadClass,
      EarthChunkGenerator.PreparedChunkBuildings preparedBuildings
   ) {
      if (roadAreas == null || roadAreas.isEmpty()) {
         return;
      }

      double worldScale = this.settings.worldScale();
      if (!(worldScale > 0.0)) {
         return;
      }

      IntArrayList areaCells = new IntArrayList(CHUNK_AREA);
      IntArrayList deckSamples = new IntArrayList(CHUNK_AREA);
      for (RoadAreaFeature area : roadAreas) {
         areaCells.clear();
         deckSamples.clear();

         for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
            int worldZ = chunkMinZ + localZ;
            for (int localX = 0; localX < CHUNK_SIDE; localX++) {
               int index = chunkIndex(localX, localZ);
               if (chunkRoadClass[index] > 0 || waterFlags[index] && waterSurfaces[index] > terrainSurfaces[index]) {
                  continue;
               }

               int worldX = chunkMinX + localX;
               if (area.containsBlock(worldX, worldZ, worldScale)) {
                  areaCells.add(index);
                  deckSamples.add(terrainSurfaces[index]);
               }
            }
         }

         if (areaCells.isEmpty() || deckSamples.isEmpty()) {
            continue;
         }

         int deckY = Mth.clamp(medianValue(deckSamples), chunkMinY, chunkMaxY);
         byte style = RoadSurfaceStyle.surfaceStyleId(area.roadClass(), area.highwayTag(), area.roadSurface(), area.subclass(), chunkMinX, chunkMinZ);
         BlockState areaState = roadStateForStyle(roadClassId(area.roadClass()), style, false);
         for (int i = 0; i < areaCells.size(); i++) {
            int index = areaCells.getInt(i);
            int localX = index % CHUNK_SIDE;
            int localZ = index / CHUNK_SIDE;
            if (preparedBuildings != null && preparedBuildings.intersectsRoad(localX, localZ, deckY)) {
               continue;
            }

            int worldX = chunkMinX + localX;
            int worldZ = chunkMinZ + localZ;
            this.clearRoadSurfaceColumn(level, chunk, cursor, worldX, worldZ, deckY, terrainSurfaces[index], chunkMinY, chunkMaxY);
            cursor.set(worldX, deckY, worldZ);
            this.setChunkBlock(level, chunk, cursor, areaState);
         }
      }
   }

   private void applyRoadPointDecorations(
      WorldGenLevel level,
      ChunkAccess chunk,
      MutableBlockPos cursor,
      int chunkMinX,
      int chunkMinZ,
      int chunkMinY,
      int chunkMaxY,
      List<OsmStreetLightFeature> roadPoints,
      byte[] chunkRoadClass,
      byte[] chunkRoadMode,
      int[] chunkRoadDeckY,
      EarthChunkGenerator.PreparedChunkBuildings preparedBuildings
   ) {
      if (roadPoints == null || roadPoints.isEmpty()) {
         return;
      }

      double worldScale = this.settings.worldScale();
      double blocksPerDegree = blocksPerDegree(worldScale);
      boolean[] occupiedDecorations = new boolean[CHUNK_AREA];
      for (OsmStreetLightFeature roadPoint : roadPoints) {
         RoadPointKind kind = roadPoint.kind();
         if (kind == null || kind == RoadPointKind.STREET_LIGHT) {
            continue;
         }

         int localX = quantizeRoadCoordinate(roadPoint.longitude() * blocksPerDegree) - chunkMinX;
         int localZ = quantizeRoadCoordinate(EarthProjection.latToBlockZ(roadPoint.latitude(), worldScale)) - chunkMinZ;
         switch (kind) {
            case TRAFFIC_SIGNAL -> this.placeRoadSignal(
               level,
               chunk,
               cursor,
               chunkMinX,
               chunkMinZ,
               chunkMinY,
               chunkMaxY,
               localX,
               localZ,
               chunkRoadClass,
               chunkRoadMode,
               chunkRoadDeckY,
               preparedBuildings,
               occupiedDecorations
            );
            case BUS_STOP -> this.placeBusStop(
               level,
               chunk,
               cursor,
               chunkMinX,
               chunkMinZ,
               chunkMinY,
               chunkMaxY,
               localX,
               localZ,
               chunkRoadClass,
               chunkRoadMode,
               chunkRoadDeckY,
               preparedBuildings,
               occupiedDecorations
            );
            case BENCH, WASTE_BASKET, RECYCLING, BICYCLE_PARKING, FOUNTAIN, BOLLARD -> this.placeRoadAmenity(
               level,
               chunk,
               cursor,
               chunkMinX,
               chunkMinZ,
               chunkMinY,
               chunkMaxY,
               localX,
               localZ,
               kind,
               chunkRoadClass,
               chunkRoadMode,
               chunkRoadDeckY,
               preparedBuildings,
               occupiedDecorations
            );
            default -> {
            }
         }
      }
   }

   private void placeRoadSignal(
      WorldGenLevel level,
      ChunkAccess chunk,
      MutableBlockPos cursor,
      int chunkMinX,
      int chunkMinZ,
      int chunkMinY,
      int chunkMaxY,
      int localX,
      int localZ,
      byte[] chunkRoadClass,
      byte[] chunkRoadMode,
      int[] chunkRoadDeckY,
      EarthChunkGenerator.PreparedChunkBuildings preparedBuildings,
      boolean[] occupiedDecorations
   ) {
      EarthChunkGenerator.RoadLightAnchor anchor = findNearestRoadPointAnchor(localX, localZ, chunkRoadClass, chunkRoadMode, chunkRoadDeckY, true);
      if (anchor == null || occupiedDecorations[anchor.index()]) {
         return;
      }

      int baseY = anchor.baseY();
      int topY = baseY + 6;
      if (preparedBuildings != null && preparedBuildings.intersectsSpan(anchor.localX(), anchor.localZ(), baseY + 1, topY)) {
         return;
      }

      int worldX = chunkMinX + anchor.localX();
      int worldZ = chunkMinZ + anchor.localZ();
      if (!this.canPlaceRoadPointDecoration(level, chunk, cursor, worldX, worldZ, baseY, topY, chunkMinY, chunkMaxY)) {
         return;
      }

      occupiedDecorations[anchor.index()] = true;
      cursor.set(worldX, baseY + 1, worldZ);
      this.setChunkBlock(level, chunk, cursor, ROAD_POINT_BASE_STATE);
      for (int y = baseY + 2; y <= baseY + 5; y++) {
         cursor.set(worldX, y, worldZ);
         this.setChunkBlock(level, chunk, cursor, ROAD_SIGNAL_POLE_STATE);
      }

      cursor.set(worldX, topY, worldZ);
      this.setChunkBlock(level, chunk, cursor, ROAD_SIGNAL_HEAD_STATE);

      Direction facing = roadLightFacingForAnchor(anchor.localX(), anchor.localZ(), chunkRoadClass);
      int headX = worldX + facing.getStepX();
      int headZ = worldZ + facing.getStepZ();
      if (this.canReplaceRoadPointColumn(level, chunk, cursor, headX, headZ, baseY + 4, topY, chunkMinX, chunkMinZ, chunkMinY, chunkMaxY)) {
         cursor.set(headX, baseY + 4, headZ);
         this.setChunkBlock(level, chunk, cursor, ROAD_SIGNAL_GREEN_STATE);
         cursor.set(headX, baseY + 5, headZ);
         this.setChunkBlock(level, chunk, cursor, ROAD_SIGNAL_YELLOW_STATE);
         cursor.set(headX, topY, headZ);
         this.setChunkBlock(level, chunk, cursor, ROAD_SIGNAL_RED_STATE);
      }
   }

   private void placeBusStop(
      WorldGenLevel level,
      ChunkAccess chunk,
      MutableBlockPos cursor,
      int chunkMinX,
      int chunkMinZ,
      int chunkMinY,
      int chunkMaxY,
      int localX,
      int localZ,
      byte[] chunkRoadClass,
      byte[] chunkRoadMode,
      int[] chunkRoadDeckY,
      EarthChunkGenerator.PreparedChunkBuildings preparedBuildings,
      boolean[] occupiedDecorations
   ) {
      EarthChunkGenerator.RoadLightAnchor anchor = findNearestRoadPointAnchor(localX, localZ, chunkRoadClass, chunkRoadMode, chunkRoadDeckY, true);
      if (anchor == null || occupiedDecorations[anchor.index()]) {
         return;
      }

      int baseY = anchor.baseY();
      int topY = baseY + 4;
      if (preparedBuildings != null && preparedBuildings.intersectsSpan(anchor.localX(), anchor.localZ(), baseY + 1, topY)) {
         return;
      }

      int worldX = chunkMinX + anchor.localX();
      int worldZ = chunkMinZ + anchor.localZ();
      if (!this.canPlaceRoadPointDecoration(level, chunk, cursor, worldX, worldZ, baseY, topY, chunkMinY, chunkMaxY)) {
         return;
      }

      occupiedDecorations[anchor.index()] = true;
      cursor.set(worldX, baseY + 1, worldZ);
      this.setChunkBlock(level, chunk, cursor, ROAD_POINT_BASE_STATE);
      for (int y = baseY + 2; y <= baseY + 3; y++) {
         cursor.set(worldX, y, worldZ);
         this.setChunkBlock(level, chunk, cursor, ROAD_SIGNAL_POLE_STATE);
      }

      cursor.set(worldX, topY, worldZ);
      this.setChunkBlock(level, chunk, cursor, ROAD_BUS_STOP_SIGN_STATE);
      Direction facing = roadLightFacingForAnchor(anchor.localX(), anchor.localZ(), chunkRoadClass);
      int panelX = worldX + facing.getStepX();
      int panelZ = worldZ + facing.getStepZ();
      if (this.canReplaceRoadPointColumn(level, chunk, cursor, panelX, panelZ, topY, topY, chunkMinX, chunkMinZ, chunkMinY, chunkMaxY)) {
         cursor.set(panelX, topY, panelZ);
         this.setChunkBlock(level, chunk, cursor, ROAD_BUS_STOP_PANEL_STATE);
      }
   }

   private void placeRoadAmenity(
      WorldGenLevel level,
      ChunkAccess chunk,
      MutableBlockPos cursor,
      int chunkMinX,
      int chunkMinZ,
      int chunkMinY,
      int chunkMaxY,
      int localX,
      int localZ,
      RoadPointKind kind,
      byte[] chunkRoadClass,
      byte[] chunkRoadMode,
      int[] chunkRoadDeckY,
      EarthChunkGenerator.PreparedChunkBuildings preparedBuildings,
      boolean[] occupiedDecorations
   ) {
      EarthChunkGenerator.RoadLightAnchor anchor = findNearestRoadPointAnchor(localX, localZ, chunkRoadClass, chunkRoadMode, chunkRoadDeckY, true);
      if (anchor == null || occupiedDecorations[anchor.index()]) {
         return;
      }

      int baseY = anchor.baseY();
      int topY = baseY + 1;
      if (preparedBuildings != null && preparedBuildings.intersectsSpan(anchor.localX(), anchor.localZ(), topY, topY)) {
         return;
      }

      int worldX = chunkMinX + anchor.localX();
      int worldZ = chunkMinZ + anchor.localZ();
      if (!this.canPlaceRoadPointDecoration(level, chunk, cursor, worldX, worldZ, baseY, topY, chunkMinY, chunkMaxY)) {
         return;
      }

      occupiedDecorations[anchor.index()] = true;
      Direction facing = roadLightFacingForAnchor(anchor.localX(), anchor.localZ(), chunkRoadClass);
      cursor.set(worldX, topY, worldZ);
      switch (kind) {
         case BENCH -> {
            BlockState bench = roadBenchState(facing);
            this.setChunkBlock(level, chunk, cursor, bench);
            Direction along = facing.getClockWise();
            int secondX = worldX + along.getStepX();
            int secondZ = worldZ + along.getStepZ();
            if (this.canReplaceRoadPointColumn(level, chunk, cursor, secondX, secondZ, topY, topY, chunkMinX, chunkMinZ, chunkMinY, chunkMaxY)) {
               cursor.set(secondX, topY, secondZ);
               this.setChunkBlock(level, chunk, cursor, bench);
            }
         }
         case WASTE_BASKET -> this.setChunkBlock(level, chunk, cursor, ROAD_WASTE_BIN_STATE);
         case RECYCLING -> this.setChunkBlock(level, chunk, cursor, ROAD_RECYCLING_BIN_STATE);
         case BICYCLE_PARKING -> {
            this.setChunkBlock(level, chunk, cursor, ROAD_BICYCLE_RACK_STATE);
            Direction along = facing.getClockWise();
            int secondX = worldX + along.getStepX();
            int secondZ = worldZ + along.getStepZ();
            if (this.canReplaceRoadPointColumn(level, chunk, cursor, secondX, secondZ, topY, topY, chunkMinX, chunkMinZ, chunkMinY, chunkMaxY)) {
               cursor.set(secondX, topY, secondZ);
               this.setChunkBlock(level, chunk, cursor, ROAD_BICYCLE_RACK_STATE);
            }
         }
         case FOUNTAIN -> this.setChunkBlock(level, chunk, cursor, ROAD_FOUNTAIN_STATE);
         case BOLLARD -> this.setChunkBlock(level, chunk, cursor, ROAD_BOLLARD_STATE);
         default -> {
         }
      }
   }

   private static BlockState roadBenchState(Direction facing) {
      return Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
   }

   private boolean canPlaceRoadPointDecoration(
      WorldGenLevel level,
      ChunkAccess chunk,
      MutableBlockPos cursor,
      int worldX,
      int worldZ,
      int baseY,
      int topY,
      int chunkMinY,
      int chunkMaxY
   ) {
      if (baseY < chunkMinY || topY > chunkMaxY) {
         return false;
      }

      cursor.set(worldX, baseY, worldZ);
      if (!isRoadDeckState(blockStateAt(level, chunk, cursor))) {
         return false;
      }

      for (int y = baseY + 1; y <= topY; y++) {
         cursor.set(worldX, y, worldZ);
         if (!isRoadLightReplaceable(blockStateAt(level, chunk, cursor))) {
            return false;
         }
      }

      return true;
   }

   private boolean canReplaceRoadPointColumn(
      WorldGenLevel level,
      ChunkAccess chunk,
      MutableBlockPos cursor,
      int worldX,
      int worldZ,
      int minY,
      int maxY,
      int chunkMinX,
      int chunkMinZ,
      int chunkMinY,
      int chunkMaxY
   ) {
      if (worldX < chunkMinX || worldX >= chunkMinX + CHUNK_SIDE || worldZ < chunkMinZ || worldZ >= chunkMinZ + CHUNK_SIDE || minY < chunkMinY || maxY > chunkMaxY) {
         return false;
      }

      for (int y = minY; y <= maxY; y++) {
         cursor.set(worldX, y, worldZ);
         if (!isRoadLightReplaceable(blockStateAt(level, chunk, cursor))) {
            return false;
         }
      }

      return true;
   }

   private static EarthChunkGenerator.RoadLightAnchor findNearestRoadPointAnchor(
      int localX, int localZ, byte[] chunkRoadClass, byte[] chunkRoadMode, int[] chunkRoadDeckY, boolean preferBoundary
   ) {
      int scanRadius = preferBoundary ? 8 : 5;
      int minX = Math.max(0, localX - scanRadius);
      int maxX = Math.min(CHUNK_MASK, localX + scanRadius);
      int minZ = Math.max(0, localZ - scanRadius);
      int maxZ = Math.min(CHUNK_MASK, localZ + scanRadius);
      if (minX > maxX || minZ > maxZ) {
         return null;
      }

      EarthChunkGenerator.RoadLightAnchor best = null;
      int bestBoundary = -1;
      int bestDistanceSq = Integer.MAX_VALUE;
      for (int z = minZ; z <= maxZ; z++) {
         for (int x = minX; x <= maxX; x++) {
            int index = chunkIndex(x, z);
            if (!isRoadPointRoadCell(index, chunkRoadClass, chunkRoadMode)) {
               continue;
            }

            int dx = x - localX;
            int dz = z - localZ;
            int distanceSq = dx * dx + dz * dz;
            int boundary = roadBoundaryScore(x, z, chunkRoadClass);
            boolean better = preferBoundary
               ? boundary > bestBoundary || boundary == bestBoundary && distanceSq < bestDistanceSq
               : distanceSq < bestDistanceSq || distanceSq == bestDistanceSq && boundary > bestBoundary;
            if (better) {
               bestBoundary = boundary;
               bestDistanceSq = distanceSq;
               best = new EarthChunkGenerator.RoadLightAnchor(x, z, chunkRoadDeckY[index], index);
            }
         }
      }

      return preferBoundary && bestBoundary <= 0 ? null : best;
   }

   private static boolean isRoadPointRoadCell(int index, byte[] chunkRoadClass, byte[] chunkRoadMode) {
      return chunkRoadClass[index] > 0 && chunkRoadMode[index] != (byte)(RoadMode.TUNNEL.ordinal() + 1);
   }

   private EarthChunkGenerator.PreparedChunkRoadLights prepareRoadLightsForChunk(
      ChunkPos pos, List<RoadFeature> roads, List<OsmStreetLightFeature> exactStreetLights,
      EarthChunkGenerator.RoadWidths widths, byte[] chunkRoadClass,
      int[] terrainSurfaces, int[] waterSurfaces, boolean[] waterFlags,
      boolean[] bridgeSupportShaftPresent, int[] bridgeSupportShaftBottomY, int[] bridgeSupportShaftTopY,
      boolean[] bridgeSupportCapPresent, int[] bridgeSupportCapBottomY, int[] bridgeSupportCapTopY,
      EarthChunkGenerator.PreparedChunkBuildings preparedBuildings
   ) {
      int minX = pos.getMinBlockX();
      int minZ = pos.getMinBlockZ();
      var lamps = StreetLightPlanner.plan(roads, exactStreetLights, this.settings.worldScale(),
         widths.main(), widths.normal(), widths.dirt(), minX, minZ, minX + CHUNK_MASK, minZ + CHUNK_MASK);
      EarthChunkGenerator.PreparedChunkRoadLights prepared = new EarthChunkGenerator.PreparedChunkRoadLights();
      for (StreetLightPlanner.Lamp lamp : lamps) {
         int localX = lamp.worldX() - minX;
         int localZ = lamp.worldZ() - minZ;
         int index = chunkIndex(localX, localZ);
         int baseY = terrainSurfaces[index];
         // Geometry sets the position; masks can veto it, but must never snap a pole onto a traffic lane.
         if (chunkRoadClass[index] > 0 || waterFlags[index] && waterSurfaces[index] > baseY) continue;
         int topY = baseY + lamp.height() + 3;
         boolean blocked = preparedBuildings != null && preparedBuildings.features().stream()
            .anyMatch(building -> building.groundContact() && building.blueprint().nearEntranceApproach(lamp.worldX(), lamp.worldZ()));
         for (int z = Math.max(0, localZ - 1); z <= Math.min(CHUNK_MASK, localZ + 1) && !blocked; z++) {
            for (int x = Math.max(0, localX - 1); x <= Math.min(CHUNK_MASK, localX + 1); x++) {
               if (preparedBuildings != null && preparedBuildings.intersectsSpan(x, z, baseY - 1, topY)
                  || intersectsRoadLightBridgeSupport(x, z, baseY - 1, topY, bridgeSupportShaftPresent,
                     bridgeSupportShaftBottomY, bridgeSupportShaftTopY, bridgeSupportCapPresent,
                     bridgeSupportCapBottomY, bridgeSupportCapTopY)) {
                  blocked = true;
                  break;
               }
            }
         }
         if (!blocked) prepared.add(new EarthChunkGenerator.PreparedRoadLight(lamp, baseY));
      }
      return prepared.isEmpty() ? null : prepared;
   }

   private static int roadBoundaryScore(int localX, int localZ, byte[] chunkRoadClass) {
      int index = chunkIndex(localX, localZ);
      if (chunkRoadClass[index] <= 0) {
         return 0;
      }

      int score = 0;
      for (Direction direction : Direction.Plane.HORIZONTAL) {
         int x = localX + direction.getStepX();
         int z = localZ + direction.getStepZ();
         if (x < 0 || x > CHUNK_MASK || z < 0 || z > CHUNK_MASK || chunkRoadClass[chunkIndex(x, z)] == 0) {
            score++;
         }
      }

      return score;
   }

   private static Direction roadLightFacingForAnchor(int localX, int localZ, byte[] chunkRoadClass) {
      for (Direction direction : Direction.Plane.HORIZONTAL) {
         int x = localX + direction.getStepX();
         int z = localZ + direction.getStepZ();
         if (x < 0 || x > CHUNK_MASK || z < 0 || z > CHUNK_MASK || chunkRoadClass[chunkIndex(x, z)] == 0) {
            return direction;
         }
      }

      return Direction.NORTH;
   }

   private void placePreparedRoadLights(WorldGenLevel level, ChunkAccess chunk) {
      long chunkKey = MinecraftVersionCompat.packChunkPos(
         MinecraftVersionCompat.chunkX(chunk.getPos()), MinecraftVersionCompat.chunkZ(chunk.getPos())
      );
      EarthChunkGenerator.PreparedChunkRoadLights prepared = this.preparedChunkRoadLights.remove(chunkKey);
      this.clearPreparedChunkStateTracking(chunkKey);
      this.placePreparedRoadLights(level, chunk, prepared);
   }

   private void placePreparedRoadLights(WorldGenLevel level, ChunkAccess chunk, EarthChunkGenerator.PreparedChunkRoadLights prepared) {
      if (prepared == null || prepared.isEmpty()) return;
      int minY = MinecraftVersionCompat.minY(chunk);
      int maxY = minY + chunk.getHeight() - 1;
      int flags = level instanceof ServerLevel ? 2 : 260;
      for (EarthChunkGenerator.PreparedRoadLight light : prepared.lights()) {
         StreetLightModel.place(light.lamp(), light.baseY(), minY, maxY,
            position -> level.hasChunk(position.getX() >> 4, position.getZ() >> 4)
               ? level.getBlockState(position) : Blocks.BARRIER.defaultBlockState(),
            (position, state) -> level.setBlock(position, state, flags));
      }
   }

   private void setChunkBlock(WorldGenLevel level, ChunkAccess chunk, MutableBlockPos cursor, BlockState state) {
      if (level == null) {
         setChunkBlockStateDiscardingBlockEntity(chunk, cursor, state);
      } else {
         level.setBlock(cursor, state, this.detailApplyFlags(level));
      }
   }

   private static void setChunkBlockStateDiscardingBlockEntity(ChunkAccess chunk, BlockPos pos, BlockState state) {
      MinecraftVersionCompat.setBlockState(chunk, pos, state);
      if (!state.hasBlockEntity()) {
         chunk.removeBlockEntity(pos);
      }
   }

   private static BlockState blockStateAt(WorldGenLevel level, ChunkAccess chunk, MutableBlockPos cursor) {
      return level == null ? chunk.getBlockState(cursor) : level.getBlockState(cursor);
   }

   private int detailApplyFlags(WorldGenLevel level) {
      return level instanceof ServerLevel ? 2 : 260;
   }

   private static int medianValue(IntArrayList values) {
      int[] sorted = values.toIntArray();
      Arrays.sort(sorted);
      return sorted[sorted.length >> 1];
   }

   private int buildingHeightBlocks(OsmBuildingFeature feature) {
      return TellusBuildingProfiles.heightBlocks(feature, this.settings.worldScale());
   }

   private int buildingMinHeightBlocks(OsmBuildingFeature feature) {
      return TellusBuildingProfiles.minHeightBlocks(feature, this.settings.worldScale());
   }

   private static String resolveBuildingGroupId(OsmBuildingFeature feature) {
      if (feature.kind() == com.yucareux.tellus.world.data.osm.OsmBuildingKind.PART) {
         String buildingId = feature.buildingId();
         return buildingId != null ? "part:" + buildingId : "part:" + feature.featureId();
      } else {
         return "footprint:" + feature.featureId();
      }
   }

   private void applyInlandFlowSurface(
      ChunkAccess chunk, ChunkSectionWriter sectionWriter, BlockPos.MutableBlockPos cursor, int x, int z, int waterY
   ) {
      int flowLevel = this.waterResolver.flowingWaterLevel(x, z, waterY);
      if (flowLevel <= 0) return;
      BlockState state = WATER_STATE.setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, flowLevel);
      int bottom = Math.max(MinecraftVersionCompat.minY(chunk), this.waterResolver.flowingWaterBottom(x, z, waterY));
      for (int y = bottom; y <= waterY; y++) {
         cursor.set(x, y, z);
         if (sectionWriter != null) {
            // The batch writer already owns this section's non-reentrant lock.
            // Never reacquire it through ChunkAccess while emitting a curtain.
            sectionWriter.setBlock(x & 15, z & 15, y, state);
         } else {
            MinecraftVersionCompat.setBlockState(chunk, cursor, state);
         }
         MinecraftVersionCompat.markPosForPostProcessing(chunk, cursor);
      }
   }

   private boolean isWaterfallSourceColumn(int worldX, int worldZ, int waterSurface) {
      for (Direction direction : Direction.Plane.HORIZONTAL) {
         WaterSurfaceResolver.WaterfallInfo waterfall = this.waterResolver.resolveWaterfallInfo(
            worldX + direction.getStepX(), worldZ + direction.getStepZ()
         );
         if (WaterSurfaceResolver.shouldScheduleFlowSource(waterSurface, waterfall)) {
            return true;
         }
      }
      return false;
   }

   private WaterSurfaceResolver.WaterChunkData resolveChunkWaterData(ChunkPos pos) {
      return this.resolveChunkWaterData(pos, null);
   }

   private WaterSurfaceResolver.WaterChunkData resolveChunkWaterData(ChunkPos pos, int[] dryTerrainSurfaces) {
      EarthChunkGenerator.WaterChunkCache cache = this.waterChunkCache.get();
      if (cache.matches(pos) && (dryTerrainSurfaces == null || !cache.data().approximate())) {
         return cache.data();
      } else {
         WaterSurfaceResolver.WaterChunkData data = this.waterResolver.resolveChunkWaterData(
            MinecraftVersionCompat.chunkX(pos), MinecraftVersionCompat.chunkZ(pos), dryTerrainSurfaces
         );
         cache.update(pos, data);
         return data;
      }
   }

   private WaterSurfaceResolver.WaterChunkData resolveExactChunkWaterData(ChunkPos pos) {
      WaterSurfaceResolver.WaterChunkData data = this.waterResolver.resolveChunkWaterDataExact(
         MinecraftVersionCompat.chunkX(pos), MinecraftVersionCompat.chunkZ(pos)
      );
      this.waterChunkCache.get().update(pos, data);
      return data;
   }

   private boolean shouldResolveApproximateWaterExactly(ChunkPos pos, int[] terrainSurfaces) {
      if (this.waterResolver.hasWaterNearChunk(MinecraftVersionCompat.chunkX(pos), MinecraftVersionCompat.chunkZ(pos))) {
         return true;
      }

      if (minSurfaceHeight(terrainSurfaces) <= this.seaLevel + 2) {
         return true;
      }

      int minX = pos.getMinBlockX();
      int minZ = pos.getMinBlockZ();
      return this.isKnownOceanLandMask(minX + 8, minZ + 8)
         || this.isKnownOceanLandMask(minX, minZ)
         || this.isKnownOceanLandMask(minX + CHUNK_MASK, minZ)
         || this.isKnownOceanLandMask(minX, minZ + CHUNK_MASK)
         || this.isKnownOceanLandMask(minX + CHUNK_MASK, minZ + CHUNK_MASK);
   }

   private boolean isKnownOceanLandMask(int worldX, int worldZ) {
      TerrainPreloadPackage.Sample preloaded = this.samplePreloadedTerrain(worldX, worldZ);
      if (preloaded != null) {
         return preloaded.landMaskKnown() && !preloaded.land();
      }

      TellusLandMaskSource.LandMaskSample sample = LAND_MASK_SOURCE.sampleLandMask(worldX, worldZ, this.settings.worldScale());
      return sample.known() && !sample.land();
   }

   private TellusVanillaCarverRunner getTellusCarverRunner(RegistryAccess registryAccess) {
      TellusVanillaCarverRunner cached = this.tellusCarverRunner;
      if (cached != null) {
         return cached;
      } else {
         synchronized (this) {
            cached = this.tellusCarverRunner;
            if (cached == null) {
               Registry<Block> blockRegistry = MinecraftVersionCompat.registry(registryAccess, Registries.BLOCK);
               Registry<NoiseGeneratorSettings> noiseSettings = MinecraftVersionCompat.registry(registryAccess, Registries.NOISE_SETTINGS);
               Holder<NoiseGeneratorSettings> vanillaCarverNoiseSettings = MinecraftVersionCompat.registryHolder(noiseSettings, NoiseGeneratorSettings.OVERWORLD);
               Holder<NoiseGeneratorSettings> adaptedCarverNoiseSettings = Objects.requireNonNull(
                  TellusNoiseSettingsAdapter.adaptToTellusHeight(
                     vanillaCarverNoiseSettings, this.minY, this.height, this.seaLevel
                  ),
                  "adaptedCarverNoiseSettings"
               );
               cached = new TellusVanillaCarverRunner(
                  this.biomeSource,
                  blockRegistry,
                  vanillaCarverNoiseSettings,
                  adaptedCarverNoiseSettings,
                  this.minY,
                  this.height,
                  this.settings.undergroundDepth()
               );
               Tellus.LOGGER.debug(
                  "Tellus caves use the live Overworld density router; biome-registered custom carvers remain unsupported"
               );
               this.tellusCarverRunner = cached;
            }

            return cached;
         }
      }
   }

   public int sampleCoverClass(int worldX, int worldZ) {
      int preloaded = this.samplePreloadedCoverClass(worldX, worldZ);
      if (preloaded != Integer.MIN_VALUE) {
         return preloaded;
      }

      return LAND_COVER_SOURCE.sampleCoverClass(worldX, worldZ, this.settings.worldScale());
   }

   public int sampleCoverClass(int worldX, int worldZ, double previewResolutionMeters) {
      int preloaded = this.samplePreloadedCoverClass(worldX, worldZ, previewResolutionMeters);
      if (preloaded != Integer.MIN_VALUE) {
         return preloaded;
      }

      return LAND_COVER_SOURCE.sampleCoverClass(worldX, worldZ, this.settings.worldScale(), previewResolutionMeters);
   }

   public int sampleVisualCoverClass(int worldX, int worldZ) {
      int rawCoverClass = this.sampleCoverClass(worldX, worldZ);
      return this.sampleVisualCoverClass(worldX, worldZ, rawCoverClass);
   }

   public int sampleVisualCoverClass(int worldX, int worldZ, int rawCoverClass) {
      return this.sampleVisualCoverClass(worldX, worldZ, rawCoverClass, this.settings.worldScale());
   }

   public int sampleVisualCoverClass(int worldX, int worldZ, int rawCoverClass, double previewResolutionMeters) {
      double worldScale = this.settings.worldScale();
      if (!shouldSampleVisualCover(worldScale, previewResolutionMeters, rawCoverClass)) {
         return rawCoverClass;
      }
      TerrainPreloadPackage.Sample preloaded = this.samplePreloadedTerrain(worldX, worldZ, previewResolutionMeters);
      if (preloaded != null) {
         return preloaded.visualCoverClass();
      }
      int visualCoverClass = LAND_COVER_SOURCE.sampleVisualCoverClass(
         worldX, worldZ, worldScale, previewResolutionMeters
      );
      return visualCoverClass == Integer.MIN_VALUE ? rawCoverClass : visualCoverClass;
   }

   /**
    * Land cover for LOD columns wider than the source pixels, which cannot blend: the raw class where
    * the full-detail border warp ({@link LandCoverTransition#warpPixelsX}) moves the column, so
    * mid-distance LOD borders bend the way chunk borders do instead of following the pixel grid.
    * Water, missing data and mangroves keep their exact raster borders.
    */
   public int sampleLodWarpedCoverClass(int worldX, int worldZ, int rawCoverClass, double previewResolutionMeters) {
      double worldScale = this.settings.worldScale();
      if (isHardRawCoverClass(rawCoverClass) || !(worldScale > 0.0) || worldScale >= ESA_WORLD_COVER_RESOLUTION_METERS) {
         return rawCoverClass;
      }
      double strength = LandCoverTransition.strength(ESA_WORLD_COVER_RESOLUTION_METERS, worldScale);
      double pixelBlocks = ESA_WORLD_COVER_RESOLUTION_METERS / worldScale;
      int warpedX = Mth.floor(worldX + LandCoverTransition.warpPixelsX(worldX, worldZ, pixelBlocks, strength) * pixelBlocks);
      int warpedZ = Mth.floor(worldZ + LandCoverTransition.warpPixelsZ(worldX, worldZ, pixelBlocks, strength) * pixelBlocks);
      if (warpedX == worldX && warpedZ == worldZ) {
         return rawCoverClass;
      }
      int warpedCoverClass = this.sampleCoverClass(warpedX, warpedZ, previewResolutionMeters);
      return warpedCoverClass == Integer.MIN_VALUE || isHardRawCoverClass(warpedCoverClass) ? rawCoverClass : warpedCoverClass;
   }

   private static boolean shouldSampleVisualCover(double worldScale, double previewResolutionMeters, int rawCoverClass) {
      return worldScale > 0.0
         && worldScale < ESA_WORLD_COVER_RESOLUTION_METERS
         && !isHardRawCoverClass(rawCoverClass)
         && effectiveCoverResolutionMeters(worldScale, previewResolutionMeters) < ESA_WORLD_COVER_RESOLUTION_METERS;
   }

   private static double effectiveCoverResolutionMeters(double worldScale, double previewResolutionMeters) {
      return Double.isFinite(previewResolutionMeters) && previewResolutionMeters > 0.0
         ? Math.max(worldScale, previewResolutionMeters)
         : worldScale;
   }

   private static boolean isHardRawCoverClass(int coverClass) {
      // Built-up land blends with its neighbours; water, missing data and mangroves stay exact.
      return LandCoverTransition.isHardClass(coverClass);
   }

   public int resolveLodTerrainSurface(int worldX, int worldZ, int coverClass, double previewResolutionMeters) {
      int surface = this.sampleSurfaceHeight(worldX, worldZ, previewResolutionMeters);
      return Mth.clamp(surface, this.minY, this.minY + this.height - 1);
   }

   public void repairLodTerrainSurfaceGrid(int[] surfaces, int[] coverClasses, int width) {
      if (surfaces.length != coverClasses.length) {
         throw new IllegalArgumentException("Mismatched LOD terrain and cover grids");
      }

      boolean[] repairMask = new boolean[surfaces.length];
      for (int index = 0; index < surfaces.length; index++) {
         int effectiveCoverClass = this.resolveEffectiveCoverClassForTerrain(coverClasses[index]);
         repairMask[index] = this.shouldRepairTerrainAnomaly(effectiveCoverClass, surfaces[index], false);
      }

      TerrainAnomalyRepair.repairHeightGrid(
         surfaces, repairMask, width, this.minY, this.minY + this.height - 1
      );
   }

   public boolean resolveLodMapterhornLandOverride(int worldX, int worldZ, double previewResolutionMeters) {
      TerrainPreloadPackage.Sample preloaded = this.samplePreloadedTerrain(worldX, worldZ, previewResolutionMeters);
      if (preloaded != null) {
         return preloaded.mapterhornLandOverride();
      }

      return ELEVATION_SOURCE.sampleResolvedPreviewElevationMeters(
         worldX, worldZ, this.settings.worldScale(), false, this.settings.demSelection(), previewResolutionMeters
      ).mapterhornLandOverride();
   }

   public int resolveLodOceanTerrainSurface(int worldX, int worldZ, int waterSurface, double previewResolutionMeters) {
      TerrainPreloadPackage.Sample preloaded = this.samplePreloadedTerrain(worldX, worldZ, previewResolutionMeters);
      if (preloaded != null && preloaded.oceanElevationSelected()) {
         int surface = this.clampOceanTerrainSurface(preloaded.terrainHeight(), waterSurface);
         return this.repairAnomalousSurfaceHeight(worldX, worldZ, surface, ESA_WATER, this.minY, this.minY + this.height - 1, true);
      }

      double elevation = ELEVATION_SOURCE.samplePreviewOceanElevationMeters(
         worldX, worldZ, this.settings.worldScale(), this.settings.demSelection(), previewResolutionMeters
      );
      int surface = Double.isNaN(elevation)
         ? this.fallbackLodOceanTerrainSurface(worldX, worldZ, waterSurface, previewResolutionMeters)
         : this.clampOceanTerrainSurface(this.scaleElevationToHeight(elevation, worldZ), waterSurface);
      return this.repairAnomalousSurfaceHeight(worldX, worldZ, surface, ESA_WATER, this.minY, this.minY + this.height - 1, true);
   }

   private int fallbackLodOceanTerrainSurface(int worldX, int worldZ, int waterSurface, double previewResolutionMeters) {
      TerrainPreloadPackage.Sample preloaded = this.samplePreloadedTerrain(worldX, worldZ, previewResolutionMeters);
      if (preloaded != null) {
         return this.clampOceanTerrainSurface(preloaded.terrainHeight(), waterSurface);
      }

      double fallbackElevation = ELEVATION_SOURCE.samplePreviewElevationMeters(
         worldX, worldZ, this.settings.worldScale(), false, this.settings.demSelection(), previewResolutionMeters
      );
      if (Double.isFinite(fallbackElevation) && fallbackElevation < 0.0) {
         return this.clampOceanTerrainSurface(this.scaleElevationToHeight(fallbackElevation, worldZ), waterSurface);
      }

      return this.clampOceanTerrainSurface(
         waterSurface - WaterSurfaceResolver.fallbackOceanDepthBlocks(
            worldX,
            worldZ,
            this.settings.worldScale(),
            this.settings.effectiveOceanicHeightScale()
         ),
         waterSurface
      );
   }

   private int clampOceanTerrainSurface(int terrainSurface, int waterSurface) {
      int clamped = Math.min(terrainSurface, waterSurface - 1);
      if (this.settings.experimentalIncreaseHeight()) {
         clamped = Math.max(clamped, this.minY + WaterSurfaceResolver.oceanFloorSupportBlocks());
      }

      return clamped;
   }

   int resolveLodMangroveWaterSurface(int worldX, int worldZ, int maxY) {
      return this.resolveMangroveWaterSurface(worldX, worldZ, maxY);
   }

   private int resolveEffectiveCoverClassForTerrain(int coverClass) {
      return coverClass;
   }

   private boolean isDryOsmEsaWater(int coverClass, boolean hasWater) {
      return this.settings.enableWater() && coverClass == ESA_WATER && !hasWater;
   }

   public int resolveDryOsmTerrainCoverClass(int worldX, int worldZ, int coverClass, boolean hasWater) {
      if (!this.isDryOsmEsaWater(coverClass, hasWater)) {
         return coverClass;
      }

      if (this.samplePreloadedTerrain(worldX, worldZ) != null) {
         return MountainSurfaceRules.ESA_BARE;
      }

      int nearest = LAND_COVER_SOURCE.sampleNearestLandCoverClassLocalOnly(
         worldX, worldZ, this.settings.worldScale(), MountainSurfaceRules.ESA_BARE
      );
      return nearest == Integer.MIN_VALUE ? MountainSurfaceRules.ESA_BARE : nearest;
   }

   private int resolveSurfaceCoverClassForTerrain(int terrainCoverClass, int visualCoverClass) {
      return MountainSurfaceRules.resolveSurfaceCoverClass(terrainCoverClass, visualCoverClass);
   }

   private WaterSurfaceResolver.WaterColumnData resolveAuxWaterColumn(int worldX, int worldZ) {
      int coverClass = this.sampleCoverClass(worldX, worldZ);
      return this.waterResolver.resolveFastColumnData(worldX, worldZ, coverClass);
   }

   private WaterSurfaceResolver.WaterColumnData resolveOsmWaterColumn(int worldX, int worldZ, int coverClass, boolean detailed) {
      return this.resolveOsmWaterColumn(worldX, worldZ, coverClass, detailed, this.settings.worldScale());
   }

   private WaterSurfaceResolver.WaterColumnData resolveOsmWaterColumn(
      int worldX, int worldZ, int coverClass, boolean detailed, double previewResolutionMeters
   ) {
      WaterSurfaceResolver.WaterColumnData column = detailed
         ? this.waterResolver.resolveColumnData(worldX, worldZ, coverClass, previewResolutionMeters)
         : this.waterResolver.resolveFastColumnData(worldX, worldZ, coverClass, previewResolutionMeters);
      return coverClass == 95 ? this.mergeMangroveWaterColumn(worldX, worldZ, column) : column;
   }

   private WaterSurfaceResolver.WaterColumnData mergeMangroveWaterColumn(
      int worldX, int worldZ, WaterSurfaceResolver.WaterColumnData baseColumn
   ) {
      if (this.settings.enableWater() && (!baseColumn.hasWater() || !baseColumn.isOcean())) {
         return baseColumn;
      }

      int terrainSurface = baseColumn.terrainSurface();
      int mangroveSurface = this.resolveMangroveWaterSurface(worldX, worldZ, Math.max(this.seaLevel, baseColumn.waterSurface()));
      int waterSurface = baseColumn.hasWater() ? Math.max(baseColumn.waterSurface(), mangroveSurface) : mangroveSurface;
      boolean hasWater = baseColumn.hasWater() || waterSurface > terrainSurface;
      if (!hasWater) {
         return baseColumn;
      } else {
         if (terrainSurface >= waterSurface) {
            terrainSurface = waterSurface - 1;
         }

         return new WaterSurfaceResolver.WaterColumnData(true, baseColumn.isOcean(), terrainSurface, waterSurface);
      }
   }

   private WaterSurfaceResolver.WaterColumnData normalizeResolvedWaterColumn(
      int worldX, int worldZ, int coverClass, int minY, int maxY, WaterSurfaceResolver.WaterColumnData column
   ) {
      int minimumTerrainY = column.hasWater() && column.isOcean()
         ? Math.min(maxY, minY + WaterSurfaceResolver.oceanFloorSupportBlocks())
         : minY;
      int terrainSurface = Mth.clamp(column.terrainSurface(), minimumTerrainY, maxY);
      if (column.isOcean() || !column.hasWater() && !this.waterResolver.isInlandBank(worldX, worldZ)) {
         terrainSurface = this.repairAnomalousSurfaceHeight(
            worldX, worldZ, terrainSurface, coverClass, minimumTerrainY, maxY, column.hasWater()
         );
      }
      if (!column.hasWater()) {
         return new WaterSurfaceResolver.WaterColumnData(false, false, terrainSurface, terrainSurface);
      } else {
         int waterSurface = Mth.clamp(column.waterSurface(), minY, maxY);
         if (terrainSurface >= waterSurface) {
            terrainSurface = Math.max(minimumTerrainY, waterSurface - 1);
         }

         return new WaterSurfaceResolver.WaterColumnData(true, column.isOcean(), terrainSurface, waterSurface);
      }
   }

   private EarthChunkGenerator.ColumnHeights columnHeightsFromWaterColumn(WaterSurfaceResolver.WaterColumnData column, int minY, int maxY) {
      int minimumTerrainY = column.hasWater() && column.isOcean()
         ? Math.min(maxY, minY + WaterSurfaceResolver.oceanFloorSupportBlocks())
         : minY;
      int terrainSurface = Mth.clamp(column.terrainSurface(), minimumTerrainY, maxY);
      if (!column.hasWater()) {
         return new EarthChunkGenerator.ColumnHeights(terrainSurface, terrainSurface, false);
      } else {
         int waterSurface = Mth.clamp(column.waterSurface(), minY, maxY);
         if (terrainSurface >= waterSurface) {
            terrainSurface = Math.max(minimumTerrainY, waterSurface - 1);
         }

         return new EarthChunkGenerator.ColumnHeights(terrainSurface, waterSurface, true);
      }
   }

   public WaterSurfaceResolver.WaterColumnData resolveLodWaterColumn(int worldX, int worldZ) {
      int coverClass = this.sampleCoverClass(worldX, worldZ);
      return this.resolveLodWaterColumn(worldX, worldZ, coverClass);
   }

   public WaterSurfaceResolver.WaterColumnData resolveLodWaterColumn(int worldX, int worldZ, int coverClass) {
      return this.resolveLodWaterColumn(worldX, worldZ, coverClass, this.settings.worldScale());
   }

   public WaterSurfaceResolver.WaterColumnData resolveLodWaterColumn(int worldX, int worldZ, int coverClass, double previewResolutionMeters) {
      if (this.settings.enableWater()) {
         WaterSurfaceResolver.WaterColumnData column = this.normalizeResolvedWaterColumn(
            worldX,
            worldZ,
            coverClass,
            this.minY,
            this.minY + this.height - 1,
            this.resolveOsmWaterColumn(worldX, worldZ, coverClass, false, previewResolutionMeters)
         );
         return this.applyLodWaterDepthProfile(column);
      }

      int surface = this.sampleSurfaceHeight(worldX, worldZ, previewResolutionMeters);
      int effectiveCoverClass = this.resolveEffectiveCoverClassForTerrain(coverClass);
      surface = this.repairAnomalousSurfaceHeight(worldX, worldZ, surface, effectiveCoverClass, this.minY, this.minY + this.height - 1);
      boolean noData = effectiveCoverClass == 0;
      boolean hasWater = effectiveCoverClass == 80 || effectiveCoverClass == 95 || noData && surface <= this.seaLevel;
      if (!hasWater) {
         return new WaterSurfaceResolver.WaterColumnData(false, false, surface, surface);
      } else {
         boolean isOcean = noData && surface <= this.seaLevel;
         int waterSurface = isOcean
            ? this.waterResolver.resolveOceanWaterSurface(worldX, worldZ)
            : Math.max(surface + 1, this.seaLevel);
         return this.applyLodWaterDepthProfile(new WaterSurfaceResolver.WaterColumnData(true, isOcean, surface, waterSurface));
      }
   }

   public WaterSurfaceResolver.WaterColumnData applyLodWaterDepthProfile(WaterSurfaceResolver.WaterColumnData column) {
      return column;
   }

   public WaterSurfaceResolver.WaterColumnData resolveLodWaterColumn(int worldX, int worldZ, int coverClass, boolean useDetailedResolver) {
      return this.resolveLodWaterColumn(worldX, worldZ, coverClass, useDetailedResolver, this.settings.worldScale());
   }

   public WaterSurfaceResolver.WaterColumnData resolveLodWaterColumn(
      int worldX, int worldZ, int coverClass, boolean useDetailedResolver, double previewResolutionMeters
   ) {
      if (!useDetailedResolver) {
         return this.resolveLodWaterColumn(worldX, worldZ, coverClass, previewResolutionMeters);
      } else if (this.settings.enableWater()) {
         return this.normalizeResolvedWaterColumn(
            worldX,
            worldZ,
            coverClass,
            this.minY,
            this.minY + this.height - 1,
            this.resolveOsmWaterColumn(worldX, worldZ, coverClass, true, previewResolutionMeters)
         );
      } else {
         int surface = this.sampleSurfaceHeight(worldX, worldZ, previewResolutionMeters);
         int effectiveCoverClass = this.resolveEffectiveCoverClassForTerrain(coverClass);
         surface = this.repairAnomalousSurfaceHeight(worldX, worldZ, surface, effectiveCoverClass, this.minY, this.minY + this.height - 1);
         if (effectiveCoverClass == 95) {
            int waterSurface = this.resolveMangroveWaterSurface(worldX, worldZ, this.seaLevel);
            boolean hasWater = waterSurface > surface;
            return new WaterSurfaceResolver.WaterColumnData(hasWater, false, surface, waterSurface);
         } else {
            return this.waterResolver.resolveColumnData(worldX, worldZ, coverClass, previewResolutionMeters);
         }
      }
   }

   public void prefetchLodWaterRegions(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
      this.prefetchLodWaterRegions(minBlockX, minBlockZ, maxBlockX, maxBlockZ, true);
   }

   public void prefetchLodWaterRegions(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, boolean allowInlineExecution) {
      int padding = OCEAN_SHORE_MAX_DISTANCE;
      TellusWorldgenSources.prefetchWaterRegionsForArea(
         minBlockX - padding, minBlockZ - padding, maxBlockX + padding, maxBlockZ + padding, this.settings, allowInlineExecution
      );
   }


   public BlockState resolveBadlandsBandBlock(int worldX, int worldZ, int y) {
      return badlandsBand(worldX, worldZ, y);
   }

   public int resolveBadlandsBandDepth(int localReliefBlocks) {
      return BadlandsTerrainPolicy.cliffBandDepth(4, localReliefBlocks);
   }

   private EarthChunkGenerator.SurfacePalette selectSurfacePalette(
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int slopeDiff,
      int convexity,
      int coverClass,
      EarthChunkGenerator.LodSurfaceProfiler profiler,
      EarthChunkGenerator.LodShorelineCache shorelineCache,
      OsmQueryMode mountainOsmQueryMode
   ) {
      boolean snowLikeTerrain = !underwater && this.isAntarcticSnowTerrain(coverClass, worldZ);
      long phaseStart = beginLodSurfaceProfiling(profiler);
      EarthChunkGenerator.SurfacePalette palette = this.selectBaseSurfacePalette(biome, worldX, worldZ, surface, coverClass);
      endLodSurfaceProfiling(profiler, "generator.basePalette", phaseStart);
      if (palette != null) {
         phaseStart = beginLodSurfaceProfiling(profiler);
         palette = this.applyShorelinePaletteOverride(
            palette, biome, worldX, worldZ, surface, underwater, slopeDiff, convexity, coverClass, profiler, shorelineCache
         );
         endLodSurfaceProfiling(profiler, "generator.shoreline", phaseStart);
      }

      if (palette == null) {
         return null;
      } else {
         phaseStart = beginLodSurfaceProfiling(profiler);
         EarthChunkGenerator.SurfacePalette resolved = this.applySlopeSurfaceOverride(
            palette, underwater, slopeDiff, convexity, coverClass, biome, worldX, worldZ, surface, profiler, snowLikeTerrain, mountainOsmQueryMode
         );
         endLodSurfaceProfiling(profiler, "generator.slopeOverride", phaseStart);
         phaseStart = beginLodSurfaceProfiling(profiler);
         EarthChunkGenerator.SurfacePalette sandResolved = this.applyOvertureSandPaletteOverride(
            resolved, worldX, worldZ, underwater, mountainOsmQueryMode
         );
         endLodSurfaceProfiling(profiler, "generator.overtureSand", phaseStart);
         EarthChunkGenerator.SurfacePalette deepslateResolved = this.applyDemDeepslateSlopePaletteOverride(
            sandResolved, biome, worldX, worldZ, underwater, slopeDiff
         );
         return applyBadlandsCliffPalette(deepslateResolved, biome, worldX, worldZ, surface, underwater, slopeDiff);
      }
   }

   private EarthChunkGenerator.SurfacePalette applyShorelinePaletteOverride(
      EarthChunkGenerator.SurfacePalette palette,
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int slopeDiff,
      int convexity,
      int coverClass,
      EarthChunkGenerator.LodSurfaceProfiler profiler,
      EarthChunkGenerator.LodShorelineCache shorelineCache
   ) {
      if (Boolean.TRUE.equals(this.lodShorelineOverrideSuppressed.get())) {
         return palette;
      }

      if (shorelineCache != null && shorelineCache.definitelyDry()) {
         return palette;
      }

      long phaseStart = beginLodSurfaceProfiling(profiler);
      EarthChunkGenerator.ShorelineContext shoreline = this.resolveShorelineContext(worldX, worldZ, surface, underwater, coverClass, shorelineCache);
      endLodSurfaceProfiling(profiler, "generator.shoreline.context", phaseStart);
      if (shoreline.kind() == MountainSurfaceRules.ShorelineKind.NONE) {
         return palette;
      } else if (underwater && (!shoreline.shallowWater() || shoreline.distanceToShore() > SHALLOW_SHORE_WATER_DISTANCE)) {
         return palette;
      } else if (shoreline.kind() == MountainSurfaceRules.ShorelineKind.INLAND && MountainSurfaceRules.isTreeCoverClass(coverClass)) {
         return palette;
      } else {
         phaseStart = beginLodSurfaceProfiling(profiler);
         byte climateGroup = climateGroupForBiome(biome);
         MountainSurfaceRules.ShorelineMaterial material = MountainSurfaceRules.classifyShorelineMaterial(
            coverClass,
            climateGroup,
            this.mountainZoneHeight(surface, worldX, worldZ),
            slopeDiff,
            convexity,
            shoreline.kind(),
            shoreline.distanceToShore(),
            biome.is(BiomeTags.IS_BADLANDS)
         );
         endLodSurfaceProfiling(profiler, "generator.shoreline.classify", phaseStart);
         return switch (material) {
            case NONE, PRESERVE_WETLAND -> palette;
            case SAND, RED_SAND -> palette;
            case GRAVEL -> EarthChunkGenerator.SurfacePalette.gravelly();
         };
      }
   }

   private EarthChunkGenerator.SurfacePalette applyOvertureSandPaletteOverride(
      EarthChunkGenerator.SurfacePalette palette, int worldX, int worldZ, boolean underwater
   ) {
      return this.applyOvertureSandPaletteOverride(
         palette, worldX, worldZ, underwater, this.resolveFullChunkOsmQueryMode()
      );
   }

   private EarthChunkGenerator.SurfacePalette applyOvertureSandPaletteOverride(
      EarthChunkGenerator.SurfacePalette palette, int worldX, int worldZ, boolean underwater, OsmQueryMode queryMode
   ) {
      if (palette == null || underwater || !this.hasOvertureSandAt(worldX, worldZ, queryMode)) {
         return palette;
      } else {
         return EarthChunkGenerator.SurfacePalette.beach();
      }
   }

   private static EarthChunkGenerator.SurfacePalette mapApproximateSurfacePalette(
      MountainSurfaceRules.ApproximateSurface approximate, EarthChunkGenerator.SurfacePalette fallback, int worldX, int worldZ
   ) {
      return switch (approximate.palette()) {
         case NONE -> fallback;
         case SNOW -> approximate.form() == MountainSurfaceRules.MountainForm.GLACIER_BASIN
            ? EarthChunkGenerator.SurfacePalette.glacier(mountainMaterialState(MountainSurfaceRules.glacierSurfaceMaterial(worldX, worldZ)))
            : EarthChunkGenerator.SurfacePalette.snowStreak(STONE_STATE);
         case STONE -> EarthChunkGenerator.SurfacePalette.landform(approximate.form(), worldX, worldZ);
         case SNOW_STREAK -> EarthChunkGenerator.SurfacePalette.snowStreak(STONE_STATE);
      };
   }

   private BlockState resolveMountainMassFillBlock(
      Holder<Biome> biome, int surfaceCoverClass, int surface, int slopeDiff, int convexity, int worldX, int worldZ
   ) {
      return this.resolveMountainMassFillBlock(biome, surfaceCoverClass, surface, slopeDiff, convexity, worldX, worldZ, this.resolveFullChunkOsmQueryMode());
   }

   private BlockState resolveMountainMassFillBlock(
      Holder<Biome> biome,
      int surfaceCoverClass,
      int surface,
      int slopeDiff,
      int convexity,
      int worldX,
      int worldZ,
      OsmQueryMode mountainOsmQueryMode
   ) {
      MountainSurfaceRules.ApproximateSurface approximate = this.classifyMountainSurface(
         surfaceCoverClass,
         this.mountainZoneHeight(surface, worldX, worldZ),
         slopeDiff,
         convexity,
         this.isAntarcticSnowTerrain(surfaceCoverClass, worldZ),
         0.0F,
         worldX,
         worldZ,
         mountainOsmQueryMode
      );
      if (approximate.palette() == MountainSurfaceRules.ApproximatePalette.SNOW
         || approximate.palette() == MountainSurfaceRules.ApproximatePalette.SNOW_STREAK) {
         return STONE_STATE;
      }

      return approximate.palette() == MountainSurfaceRules.ApproximatePalette.STONE ? STONE_STATE : null;
   }

   private EarthChunkGenerator.ShorelineContext resolveShorelineContext(
      int worldX, int worldZ, int surface, boolean underwater, int coverClass, EarthChunkGenerator.LodShorelineCache shorelineCache
   ) {
      return this.resolveShorelineContext(worldX, worldZ, surface, underwater, coverClass, shorelineCache, true);
   }

   private EarthChunkGenerator.ShorelineContext resolveShorelineContext(
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int coverClass,
      EarthChunkGenerator.LodShorelineCache shorelineCache,
      boolean applyNoiseMask
   ) {
      return underwater
         ? this.resolveShallowWaterShorelineContext(worldX, worldZ, surface, coverClass, shorelineCache)
         : this.resolveLandShorelineContext(worldX, worldZ, shorelineCache, applyNoiseMask);
   }

   private EarthChunkGenerator.ShorelineContext resolveLandShorelineContext(
      int worldX, int worldZ, EarthChunkGenerator.LodShorelineCache shorelineCache, boolean applyNoiseMask
   ) {
      if (shorelineCache != null && shorelineCache.definitelyDry()) {
         return EarthChunkGenerator.ShorelineContext.NONE;
      }

      int bestOceanDistance = Integer.MAX_VALUE;
      int bestInlandDistance = Integer.MAX_VALUE;

      for (int dz = -OCEAN_SHORE_MAX_DISTANCE; dz <= OCEAN_SHORE_MAX_DISTANCE; dz++) {
         int z = worldZ + dz;

         for (int dx = -OCEAN_SHORE_MAX_DISTANCE; dx <= OCEAN_SHORE_MAX_DISTANCE; dx++) {
            int distance = shorelineDistance(dx, dz);
            if (distance <= 0 || distance > OCEAN_SHORE_MAX_DISTANCE) {
               continue;
            }

            int x = worldX + dx;
            WaterSurfaceResolver.WaterInfo info = shorelineCache != null
               ? shorelineCache.resolveFastWaterInfo(x, z)
               : this.resolveFastWaterInfoForShoreline(x, z);
            if (!info.isWater()) {
               continue;
            }

            if (info.isOcean()) {
               if (distance < bestOceanDistance) {
                  bestOceanDistance = distance;
               }
            } else if (distance <= INLAND_SHORE_MAX_DISTANCE && distance < bestInlandDistance) {
               bestInlandDistance = distance;
            }
         }
      }

      MountainSurfaceRules.ShorelineKind bestKind = MountainSurfaceRules.ShorelineKind.NONE;
      int bestDistance = Integer.MAX_VALUE;
      if (bestOceanDistance <= OCEAN_SHORE_MAX_DISTANCE) {
         bestKind = MountainSurfaceRules.ShorelineKind.OCEAN;
         bestDistance = bestOceanDistance;
      }

      if (bestInlandDistance <= INLAND_SHORE_MAX_DISTANCE && bestInlandDistance < bestDistance) {
         bestKind = MountainSurfaceRules.ShorelineKind.INLAND;
         bestDistance = bestInlandDistance;
      }

      if (bestKind == MountainSurfaceRules.ShorelineKind.NONE || applyNoiseMask && !this.passesShorelineEdgeNoise(bestKind, bestDistance, worldX, worldZ)) {
         return EarthChunkGenerator.ShorelineContext.NONE;
      } else {
         return new EarthChunkGenerator.ShorelineContext(bestKind, bestDistance, false);
      }
   }

   private EarthChunkGenerator.ShorelineContext resolveShallowWaterShorelineContext(
      int worldX, int worldZ, int surface, int coverClass, EarthChunkGenerator.LodShorelineCache shorelineCache
   ) {
      WaterSurfaceResolver.WaterInfo info = shorelineCache != null
         ? shorelineCache.resolveFastWaterInfo(worldX, worldZ)
         : this.waterResolver.resolveFastWaterInfo(worldX, worldZ, coverClass);
      if (!info.isWater()) {
         return EarthChunkGenerator.ShorelineContext.NONE;
      } else {
         int waterDepth = info.surface() - surface;
         if (waterDepth <= 0 || waterDepth > SHALLOW_SHORE_WATER_DEPTH) {
            return EarthChunkGenerator.ShorelineContext.NONE;
         } else {
            MountainSurfaceRules.ShorelineKind shoreKind = info.isOcean()
               ? MountainSurfaceRules.ShorelineKind.OCEAN
               : MountainSurfaceRules.ShorelineKind.INLAND;
            int bestLandDistance = Integer.MAX_VALUE;

            for (int dz = -SHALLOW_SHORE_WATER_DISTANCE; dz <= SHALLOW_SHORE_WATER_DISTANCE; dz++) {
               int z = worldZ + dz;

               for (int dx = -SHALLOW_SHORE_WATER_DISTANCE; dx <= SHALLOW_SHORE_WATER_DISTANCE; dx++) {
                  int distance = shorelineDistance(dx, dz);
                  if (distance <= 0 || distance > SHALLOW_SHORE_WATER_DISTANCE) {
                     continue;
                  }

                  int x = worldX + dx;
                  WaterSurfaceResolver.WaterInfo neighborInfo = shorelineCache != null
                     ? shorelineCache.resolveFastWaterInfo(x, z)
                     : this.resolveFastWaterInfoForShoreline(x, z);
                  if (!neighborInfo.isWater() && distance < bestLandDistance) {
                     bestLandDistance = distance;
                  }
               }
            }

            return bestLandDistance <= SHALLOW_SHORE_WATER_DISTANCE
               ? new EarthChunkGenerator.ShorelineContext(shoreKind, bestLandDistance, true)
               : EarthChunkGenerator.ShorelineContext.NONE;
            }
      }
   }

   private WaterSurfaceResolver.WaterInfo resolveFastWaterInfoForShoreline(int worldX, int worldZ) {
      int coverClass = this.sampleCoverClass(worldX, worldZ);
      return this.resolveFastWaterInfoForShoreline(worldX, worldZ, coverClass);
   }

   private WaterSurfaceResolver.WaterInfo resolveFastWaterInfoForShoreline(int worldX, int worldZ, double previewResolutionMeters) {
      int coverClass = this.sampleCoverClass(worldX, worldZ, previewResolutionMeters);
      return this.resolveFastWaterInfoForShoreline(worldX, worldZ, coverClass);
   }

   private WaterSurfaceResolver.WaterInfo resolveFastWaterInfoForShoreline(int worldX, int worldZ, int coverClass) {
      return this.waterResolver.resolveFastWaterInfo(worldX, worldZ, coverClass);
   }

   private EarthChunkGenerator.FullChunkOceanBeachCache buildFullChunkOceanBeachCache(
      int chunkMinX, int chunkMinZ, boolean[] waterFlags, boolean[] oceanFlags
   ) {
      int radius = FULL_CHUNK_OCEAN_BEACH_MAX_DISTANCE;
      if (radius <= 0 || !this.settings.enableWater()) {
         return EarthChunkGenerator.FullChunkOceanBeachCache.disabled(chunkMinX, chunkMinZ);
      } else {
         int side = CHUNK_SIDE + radius * 2;
         boolean[] oceanMask = new boolean[side * side];
         boolean hasOcean = false;

         for (int dz = -radius; dz < CHUNK_SIDE + radius; dz++) {
            int worldZ = chunkMinZ + dz;
            int row = (dz + radius) * side;

            for (int dx = -radius; dx < CHUNK_SIDE + radius; dx++) {
               int worldX = chunkMinX + dx;
               boolean ocean;
               if (dx >= 0 && dx < CHUNK_SIDE && dz >= 0 && dz < CHUNK_SIDE) {
                  int chunkIndex = chunkIndex(dx, dz);
                  ocean = waterFlags[chunkIndex] && oceanFlags[chunkIndex];
               } else {
                  WaterSurfaceResolver.WaterInfo info = this.resolveFastWaterInfoForShoreline(worldX, worldZ);
                  ocean = info.isWater() && info.isOcean();
               }

               oceanMask[row + dx + radius] = ocean;
               hasOcean |= ocean;
            }
         }

         if (!hasOcean) {
            return EarthChunkGenerator.FullChunkOceanBeachCache.disabled(chunkMinX, chunkMinZ);
         } else {
            byte[] oceanDistances = new byte[CHUNK_AREA];
            Arrays.fill(oceanDistances, (byte)-1);

            for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
               for (int localX = 0; localX < CHUNK_SIDE; localX++) {
                  int chunkIndex = chunkIndex(localX, localZ);
                  if (!waterFlags[chunkIndex]) {
                     int bestDistance = Integer.MAX_VALUE;

                     for (int dz = -radius; dz <= radius; dz++) {
                        int row = (localZ + dz + radius) * side;

                        for (int dx = -radius; dx <= radius; dx++) {
                           int distance = shorelineDistance(dx, dz);
                           if (distance > 0 && distance <= radius && oceanMask[row + localX + dx + radius] && distance < bestDistance) {
                              bestDistance = distance;
                           }
                        }
                     }

                     if (bestDistance != Integer.MAX_VALUE) {
                        oceanDistances[chunkIndex] = (byte)bestDistance;
                     }
                  }
               }
            }

            return new EarthChunkGenerator.FullChunkOceanBeachCache(chunkMinX, chunkMinZ, oceanDistances, true);
         }
      }
   }

   private EarthChunkGenerator.LodShorelineCache buildDenseLodShorelineCache(
      int minX, int maxX, int minZ, int maxZ, int width, int height, double previewResolutionMeters
   ) {
      WaterSurfaceResolver.WaterInfo[] waterInfos = new WaterSurfaceResolver.WaterInfo[width * height];
      boolean hasWater = false;
      int index = 0;

      for (int z = minZ; z <= maxZ; z++) {
         for (int x = minX; x <= maxX; x++) {
            WaterSurfaceResolver.WaterInfo info = this.resolveFastWaterInfoForShoreline(x, z, previewResolutionMeters);
            waterInfos[index++] = info;
            hasWater |= info.isWater();
         }
      }

      return new EarthChunkGenerator.DenseLodShorelineCache(
         minX, maxX, minZ, maxZ, width, waterInfos, hasWater, previewResolutionMeters
      );
   }

   private EarthChunkGenerator.LodSharedTerrainCache buildDenseLodSharedTerrainCache(
      int coverMinX,
      int coverMaxX,
      int coverMinZ,
      int coverMaxZ,
      int coverWidth,
      int coverHeight,
      int shoreMinX,
      int shoreMaxX,
      int shoreMinZ,
      int shoreMaxZ,
      int shoreWidth,
      int shoreHeight,
      double previewResolutionMeters
   ) {
      byte[] rawCoverClasses = new byte[coverWidth * coverHeight];
      byte[] visualCoverClasses = new byte[coverWidth * coverHeight];
      byte[] surfaceCoverClasses = new byte[coverWidth * coverHeight];
      WaterSurfaceResolver.WaterInfo[] waterInfos = new WaterSurfaceResolver.WaterInfo[shoreWidth * shoreHeight];
      boolean hasWater = false;
      int coverIndex = 0;

      for (int z = coverMinZ; z <= coverMaxZ; z++) {
         boolean inShorelineZ = z >= shoreMinZ && z <= shoreMaxZ;
         int shorelineRow = inShorelineZ ? (z - shoreMinZ) * shoreWidth : -1;

         for (int x = coverMinX; x <= coverMaxX; x++) {
            int rawCoverClass = this.sampleCoverClass(x, z, previewResolutionMeters);
            int visualCoverClass = this.sampleVisualCoverClass(x, z, rawCoverClass, previewResolutionMeters);
            int surfaceCoverClass = MountainSurfaceRules.resolveSurfaceCoverClass(rawCoverClass, visualCoverClass);
            rawCoverClasses[coverIndex] = (byte)rawCoverClass;
            visualCoverClasses[coverIndex] = (byte)visualCoverClass;
            surfaceCoverClasses[coverIndex] = (byte)surfaceCoverClass;
            if (inShorelineZ && x >= shoreMinX && x <= shoreMaxX) {
               WaterSurfaceResolver.WaterInfo info = this.resolveFastWaterInfoForShoreline(x, z, rawCoverClass);
               waterInfos[shorelineRow + (x - shoreMinX)] = info;
               hasWater |= info.isWater();
            }

            coverIndex++;
         }
      }

      EarthChunkGenerator.DenseLodShorelineCache shorelineCache = new EarthChunkGenerator.DenseLodShorelineCache(
         shoreMinX, shoreMaxX, shoreMinZ, shoreMaxZ, shoreWidth, waterInfos, hasWater, previewResolutionMeters
      );
      EarthChunkGenerator.DenseLodMountainTransitionCache mountainTransitionCache = new EarthChunkGenerator.DenseLodMountainTransitionCache(
         coverMinX, coverMaxX, coverMinZ, coverMaxZ, coverWidth, surfaceCoverClasses, previewResolutionMeters
      );
      return new EarthChunkGenerator.DenseLodSharedTerrainCache(
         coverMinX,
         coverMaxX,
         coverMinZ,
         coverMaxZ,
         coverWidth,
         rawCoverClasses,
         visualCoverClasses,
         shorelineCache,
         mountainTransitionCache,
         previewResolutionMeters
      );
   }

   private boolean passesShorelineEdgeNoise(MountainSurfaceRules.ShorelineKind shoreKind, int distanceToShore, int worldX, int worldZ) {
      int maxDistance = shoreKind == MountainSurfaceRules.ShorelineKind.OCEAN ? OCEAN_SHORE_MAX_DISTANCE : INLAND_SHORE_MAX_DISTANCE;
      if (distanceToShore > maxDistance) {
         return false;
      } else {
         int solidDistance = Math.max(1, maxDistance - SHORELINE_OUTER_NOISE_BAND);
         if (distanceToShore <= solidDistance) {
            return true;
         } else {
            double mask = this.sampleLowFrequencyMask(
               worldX,
               worldZ,
               shoreKind == MountainSurfaceRules.ShorelineKind.OCEAN ? 80 : 56,
               shoreKind == MountainSurfaceRules.ShorelineKind.OCEAN ? 8654246259872604351L : 6417398719980302667L
            );
            int edgeIndex = distanceToShore - solidDistance;
            double threshold = edgeIndex >= SHORELINE_OUTER_NOISE_BAND ? 0.72 : 0.38;
            return mask >= threshold;
         }
      }
   }

   private static int shorelineDistance(int dx, int dz) {
      if (dx == 0 && dz == 0) {
         return 0;
      } else {
         return (int)Math.ceil(Math.sqrt((double)(dx * dx + dz * dz)));
      }
   }

   private EarthChunkGenerator.SurfacePalette applySlopeSurfaceOverride(
      EarthChunkGenerator.SurfacePalette palette,
      boolean underwater,
      int slopeDiff,
      int convexity,
      int coverClass,
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      EarthChunkGenerator.LodSurfaceProfiler profiler,
      boolean snowLikeTerrain
   ) {
      return this.applySlopeSurfaceOverride(
         palette,
         underwater,
         slopeDiff,
         convexity,
         coverClass,
         biome,
         worldX,
         worldZ,
         surface,
         profiler,
         snowLikeTerrain,
         this.resolveFullChunkOsmQueryMode()
      );
   }

   private EarthChunkGenerator.SurfacePalette applySlopeSurfaceOverride(
      EarthChunkGenerator.SurfacePalette palette,
      boolean underwater,
      int slopeDiff,
      int convexity,
      int coverClass,
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      EarthChunkGenerator.LodSurfaceProfiler profiler,
      boolean snowLikeTerrain,
      OsmQueryMode mountainOsmQueryMode
   ) {
      if (underwater) {
         return palette;
      } else if (preservesBiomeSurfacePalette(biome)) {
         return palette;
      } else {
         int zoneHeight = this.mountainZoneHeight(surface, worldX, worldZ);
         long phaseStart = beginLodSurfaceProfiling(profiler);
         MountainSurfaceRules.ApproximateSurface mountainSurface = this.classifyMountainSurface(
            coverClass, zoneHeight, slopeDiff, convexity, snowLikeTerrain, 0.0F, worldX, worldZ, mountainOsmQueryMode
         );
         endLodSurfaceProfiling(profiler, "generator.slope.context", phaseStart);
         if (mountainSurface.isSnow() || mountainSurface.isMountain()) {
            return mapApproximateSurfacePalette(mountainSurface, palette, worldX, worldZ);
         } else if (!isSoilPalette(palette) || coverClass == MountainSurfaceRules.ESA_TREE_COVER) {
            return palette;
         } else if (mountainSurface.form() == MountainSurfaceRules.MountainForm.ALPINE_MEADOW) {
            return alpineMeadowSurfacePalette(worldX, worldZ, slopeDiff);
         } else {
            // Steep turf keeps its land cover but breaks into rock outcrops as it steepens.
            return SurfacePatchNoise.covers(worldX, worldZ, EXPOSED_ROCK_SALT, MountainSurfaceRules.exposedRockPercent(slopeDiff))
               ? EarthChunkGenerator.SurfacePalette.stonyPeaks()
               : palette;
         }
      }
   }

   private float sampleMountainVegetationWeightUncached(int worldX, int worldZ, int heightAboveSea, double previewResolutionMeters) {
      int surfaceCoverClass = this.mountainSamplingCache(previewResolutionMeters).surfaceCoverClass(worldX, worldZ);
      return MountainSurfaceRules.vegetationTransitionWeightForSurfaceCoverClass(surfaceCoverClass, heightAboveSea);
   }

   private double sampleLowFrequencyMask(int worldX, int worldZ, int cellSize, long salt) {
      int cellX = Math.floorDiv(worldX, cellSize);
      int cellZ = Math.floorDiv(worldZ, cellSize);
      double fracX = (double)Math.floorMod(worldX, cellSize) / cellSize;
      double fracZ = (double)Math.floorMod(worldZ, cellSize) / cellSize;
      double v00 = this.hashedCellNoise(cellX, cellZ, salt);
      double v10 = this.hashedCellNoise(cellX + 1, cellZ, salt);
      double v01 = this.hashedCellNoise(cellX, cellZ + 1, salt);
      double v11 = this.hashedCellNoise(cellX + 1, cellZ + 1, salt);
      double i0 = Mth.lerp(fracX, v00, v10);
      double i1 = Mth.lerp(fracX, v01, v11);
      return Mth.lerp(fracZ, i0, i1);
   }

   private double hashedCellNoise(int cellX, int cellZ, long salt) {
      long seed = this.worldSeed ^ salt ^ cellX * 341873128712L ^ cellZ * 132897987541L;
      seed ^= seed >>> 33;
      seed *= -49064778989728563L;
      seed ^= seed >>> 33;
      seed *= -4265267296055464877L;
      seed ^= seed >>> 33;
      long bits = seed >>> 11 & 9007199254740991L;
      return bits / 9.007199E15F;
   }

   private static int sampleSlopeDiffCached(int[] heightGrid, int gridSize, int step, int centerIndex, int surface) {
      int east = heightGrid[centerIndex + step];
      int west = heightGrid[centerIndex - step];
      int north = heightGrid[centerIndex - step * gridSize];
      int south = heightGrid[centerIndex + step * gridSize];
      return Math.max(Math.max(Math.abs(east - surface), Math.abs(west - surface)), Math.max(Math.abs(north - surface), Math.abs(south - surface)));
   }

   private static long beginLodSurfaceProfiling(EarthChunkGenerator.LodSurfaceProfiler profiler) {
      return profiler == null ? 0L : System.nanoTime();
   }

   private static void endLodSurfaceProfiling(EarthChunkGenerator.LodSurfaceProfiler profiler, String phase, long startNanos) {
      if (profiler != null) {
         profiler.addPhase(phase, System.nanoTime() - startNanos);
      }
   }

   private static void profilerAddPhase(EarthChunkGenerator.LodSurfaceProfiler profiler, String phase, long nanos) {
      if (profiler != null) {
         profiler.addPhase(phase, nanos);
      }
   }

   private static long beginFullChunkProfiling() {
      return EarthChunkGenerator.FullChunkPerf.now();
   }

   private static long elapsedFullChunkProfilingSince(long startNanos) {
      return EarthChunkGenerator.FullChunkPerf.elapsedSince(startNanos);
   }

   private static void endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase phase, long startNanos) {
      EarthChunkGenerator.FullChunkPerf.record(phase, elapsedFullChunkProfilingSince(startNanos));
   }

   private static void recordFullChunkProfiling(EarthChunkGenerator.FullChunkPhase phase, long nanos) {
      EarthChunkGenerator.FullChunkPerf.record(phase, nanos);
   }

   private static void recordFullChunkProfilingCount(EarthChunkGenerator.FullChunkPhase phase, int count) {
      EarthChunkGenerator.FullChunkPerf.recordCount(phase, count);
   }

   static void recordTerrainStreamingPrefetchQueueRejection() {
      EarthChunkGenerator.TerrainStreamingPerf.recordPrefetchQueueRejection();
   }

   private static int sampleConvexityCached(int[] heightGrid, int gridSize, int step, int centerIndex, int surface) {
      int east = heightGrid[centerIndex + step];
      int west = heightGrid[centerIndex - step];
      int north = heightGrid[centerIndex - step * gridSize];
      int south = heightGrid[centerIndex + step * gridSize];
      int neighborAverage = (east + west + north + south) / 4;
      return neighborAverage - surface;
   }

   private static boolean isSoilPalette(EarthChunkGenerator.SurfacePalette palette) {
      return isSoilBlock(palette.filler());
   }

   private static boolean preservesBiomeSurfacePalette(Holder<Biome> biome) {
      return MountainSurfaceRules.preservesBiomeSurfacePalette(
         biome.is(Biomes.DESERT), biome.is(BiomeTags.IS_BADLANDS)
      );
   }

   private static boolean isSoilBlock(BlockState state) {
      return state.is(BlockTags.DIRT) || state.is(Blocks.MUD);
   }

   private boolean isAntarcticSnowTerrain(int coverClass, int worldZ) {
      return this.antarcticSnowPolicy.shouldUseSnowFallback(coverClass, worldZ);
   }

   private EarthChunkGenerator.SurfacePalette selectBaseSurfacePalette(Holder<Biome> biome, int worldX, int worldZ, int surface, int coverClass) {
      if (this.settings.climateBasedBuiltUpTerrain() && coverClass == MountainSurfaceRules.ESA_BUILT) {
         coverClass = climateBasedBuiltUpCoverClass(biome);
      }

      boolean randomLandPatch = this.settings.randomBiomes() && RandomBiomeMixer.isLandPatchActive(this.settings, worldX, worldZ);
      if (biome.is(BiomeTags.IS_OCEAN) || (biome.is(BiomeTags.IS_RIVER) && MountainSurfaceRules.isWaterLikeCoverClass(coverClass))) {
         return this.oceanFloorPalette(worldX, worldZ);
      } else if (!randomLandPatch && coverClass == MountainSurfaceRules.ESA_TREE_COVER) {
         return this.coverDrivenSurfacePalette(coverClass, climateGroupForBiome(biome), worldX, worldZ, surface);
      } else if (biome.is(Biomes.MUSHROOM_FIELDS)) {
         return EarthChunkGenerator.SurfacePalette.mushroomFields();
      } else if (biome.is(Biomes.BEACH) || biome.is(Biomes.SNOWY_BEACH)) {
         return EarthChunkGenerator.SurfacePalette.beach();
      } else if (biome.is(Biomes.STONY_SHORE)) {
         return EarthChunkGenerator.SurfacePalette.stonyPeaks();
      } else if (biome.is(BiomeTags.IS_BADLANDS)) {
         return EarthChunkGenerator.SurfacePalette.badlands(badlandsPlateauTop(worldX, worldZ));
      } else if (biome.is(Biomes.DESERT)) {
         return EarthChunkGenerator.SurfacePalette.desert();
      } else if (biome.is(Biomes.MANGROVE_SWAMP)) {
         return EarthChunkGenerator.SurfacePalette.mangrove();
      } else if (biome.is(Biomes.SWAMP)) {
         return EarthChunkGenerator.SurfacePalette.swamp();
      } else if (biome.is(Biomes.STONY_PEAKS)) {
         return EarthChunkGenerator.SurfacePalette.stonyPeaks();
      } else if (biome.is(Biomes.WINDSWEPT_GRAVELLY_HILLS)) {
         return EarthChunkGenerator.SurfacePalette.gravelly();
      } else {
         // Cold biomes describe climate; only mapped snow cover supplies snow.
         byte climateGroup = climateGroupForBiome(biome);
         EarthChunkGenerator.SurfacePalette coverPalette = this.coverDrivenSurfacePalette(coverClass, climateGroup, worldX, worldZ, surface);
         return isGravellyPalette(coverPalette) && shouldUseDefaultForGrassGravel(biome, coverClass, worldX, worldZ)
            ? EarthChunkGenerator.SurfacePalette.defaultOverworld()
            : coverPalette;
      }
   }

   private EarthChunkGenerator.SurfacePalette coverDrivenSurfacePalette(int coverClass, byte climateGroup, int worldX, int worldZ, int surface) {
      int roll = surfaceVariant(worldX, worldZ, 23);
      int zoneHeight = this.mountainZoneHeight(surface, worldX, worldZ);

      return switch (coverClass) {
         case 10 -> climateGroup != 4 && climateGroup != 5
            ? (
               climateGroup == 2
                  ? (roll < 70 ? EarthChunkGenerator.SurfacePalette.steppe() : EarthChunkGenerator.SurfacePalette.defaultOverworld())
                  : (
                     climateGroup == 1
                        ? (roll < 35 ? EarthChunkGenerator.SurfacePalette.rooted() : EarthChunkGenerator.SurfacePalette.defaultOverworld())
                        : (roll < 20 ? EarthChunkGenerator.SurfacePalette.rooted() : EarthChunkGenerator.SurfacePalette.defaultOverworld())
                  )
            )
            : (roll < 65 ? EarthChunkGenerator.SurfacePalette.podzolic() : EarthChunkGenerator.SurfacePalette.defaultOverworld());
         case 20 -> zoneHeight >= MountainSurfaceRules.SURFACE_ALPINE_HEIGHT_ABOVE_SEA && roll < 70
            ? EarthChunkGenerator.SurfacePalette.gravelly()
            : (
               climateGroup == 2
                  ? EarthChunkGenerator.SurfacePalette.steppe()
                  : (
                     climateGroup != 4 && climateGroup != 5
                        ? (roll < 40 ? EarthChunkGenerator.SurfacePalette.steppe() : EarthChunkGenerator.SurfacePalette.defaultOverworld())
                        : EarthChunkGenerator.SurfacePalette.podzolic()
                  )
            );
         case 30 -> zoneHeight >= MountainSurfaceRules.SURFACE_ALPINE_HEIGHT_ABOVE_SEA && roll < 35
            ? EarthChunkGenerator.SurfacePalette.gravelly()
            : (
               climateGroup == 2
                  ? (roll < 65 ? EarthChunkGenerator.SurfacePalette.steppe() : EarthChunkGenerator.SurfacePalette.defaultOverworld())
                  : (
                     climateGroup != 4 && climateGroup != 5
                        ? EarthChunkGenerator.SurfacePalette.defaultOverworld()
                        : (roll < 40 ? EarthChunkGenerator.SurfacePalette.podzolic() : EarthChunkGenerator.SurfacePalette.defaultOverworld())
                  )
            );
         case 40 -> climateGroup == 2
            ? EarthChunkGenerator.SurfacePalette.steppe()
            : (roll < 65 ? EarthChunkGenerator.SurfacePalette.rooted() : EarthChunkGenerator.SurfacePalette.defaultOverworld());
         case 50 -> roll < 45 ? EarthChunkGenerator.SurfacePalette.gravelly() : EarthChunkGenerator.SurfacePalette.stonyPeaks();
         case 60 -> zoneHeight >= MountainSurfaceRules.SURFACE_ALPINE_HEIGHT_ABOVE_SEA
            ? EarthChunkGenerator.SurfacePalette.stonyPeaks()
            : (
               climateGroup == 2
                  ? (roll < 55
                     ? EarthChunkGenerator.SurfacePalette.desert()
                     : EarthChunkGenerator.SurfacePalette.badlands(badlandsPlateauTop(worldX, worldZ)))
                  : (
                     climateGroup != 4 && climateGroup != 5
                        ? EarthChunkGenerator.SurfacePalette.gravelly()
                        : (roll < 70 ? EarthChunkGenerator.SurfacePalette.gravelly() : EarthChunkGenerator.SurfacePalette.stonyPeaks())
                  )
            );
         case 70 -> EarthChunkGenerator.SurfacePalette.snowy();
         case 90 -> roll < 40 ? EarthChunkGenerator.SurfacePalette.swamp() : EarthChunkGenerator.SurfacePalette.wetland();
         case 95 -> EarthChunkGenerator.SurfacePalette.mangrove();
         case 100 -> zoneHeight >= MountainSurfaceRules.SURFACE_ALPINE_HEIGHT_ABOVE_SEA && roll < 60
            ? EarthChunkGenerator.SurfacePalette.stonyPeaks()
            : (roll < 35 ? EarthChunkGenerator.SurfacePalette.mossy() : EarthChunkGenerator.SurfacePalette.defaultOverworld());
         default -> climateGroup != 4 && climateGroup != 5
            ? EarthChunkGenerator.SurfacePalette.defaultOverworld()
            : EarthChunkGenerator.SurfacePalette.podzolic();
      };
   }

   private static int climateBasedBuiltUpCoverClass(Holder<Biome> biome) {
      if (biome == null) {
         return MountainSurfaceRules.ESA_GRASSLAND;
      } else if (biome.is(BiomeTags.IS_BADLANDS)
         || biome.is(Biomes.DESERT)
         || biome.is(Biomes.STONY_PEAKS)
         || biome.is(Biomes.WINDSWEPT_GRAVELLY_HILLS)) {
         return MountainSurfaceRules.ESA_BARE;
      } else if (biome.is(BiomeTags.IS_JUNGLE)
         || biome.is(BiomeTags.IS_TAIGA)
         || biome.is(Biomes.FOREST)
         || biome.is(Biomes.BIRCH_FOREST)
         || biome.is(Biomes.DARK_FOREST)
         || biome.is(Biomes.OLD_GROWTH_BIRCH_FOREST)
         || biome.is(Biomes.OLD_GROWTH_PINE_TAIGA)
         || biome.is(Biomes.OLD_GROWTH_SPRUCE_TAIGA)) {
         return MountainSurfaceRules.ESA_TREE_COVER;
      } else if (biome.is(BiomeTags.IS_SAVANNA)) {
         return MountainSurfaceRules.ESA_SHRUBLAND;
      } else {
         return MountainSurfaceRules.ESA_GRASSLAND;
      }
   }

   private static byte climateGroupForBiome(Holder<Biome> biome) {
      if (biome.is(Biomes.MANGROVE_SWAMP) || biome.is(BiomeTags.IS_JUNGLE) || biome.is(BiomeTags.IS_SAVANNA)) {
         return 1;
      } else if (biome.is(Biomes.DESERT) || biome.is(BiomeTags.IS_BADLANDS)) {
         return 2;
      } else if (biome.is(Biomes.FROZEN_PEAKS) || biome.is(Biomes.SNOWY_PLAINS) || biome.is(Biomes.SNOWY_SLOPES) || biome.is(Biomes.ICE_SPIKES)) {
         return 5;
      } else if (biome.is(BiomeTags.IS_TAIGA) || biome.is(Biomes.GROVE) || biome.is(Biomes.JAGGED_PEAKS) || biome.is(Biomes.WINDSWEPT_GRAVELLY_HILLS)) {
         return 4;
      } else {
         return (byte)(!biome.is(BiomeTags.IS_OCEAN) && !biome.is(BiomeTags.IS_RIVER) ? 3 : 0);
      }
   }

   private static EarthChunkGenerator.SurfacePalette alpineMeadowSurfacePalette(int worldX, int worldZ, int slopeDiff) {
      // Steeper highland turf carries scree patches rather than turning to gravel outright.
      return SurfacePatchNoise.covers(worldX, worldZ, ALPINE_SCREE_SALT, MountainSurfaceRules.alpineMeadowScreePercent(slopeDiff))
         ? EarthChunkGenerator.SurfacePalette.gravelly()
         : EarthChunkGenerator.SurfacePalette.alpineMeadow();
   }

   private static boolean shouldUseDefaultForGrassGravel(Holder<Biome> biome, int coverClass, int worldX, int worldZ) {
      if (coverClass == 50) {
         return false;
      } else if (biome.is(Biomes.PLAINS)) {
         return shouldUseDefaultForPlainsGrassGravel(biome, worldX, worldZ, 211, 90);
      } else {
         return biome.is(Biomes.MEADOW) && surfaceVariant(worldX, worldZ, 211) < 50;
      }
   }

   private static boolean shouldUseDefaultForPlainsGrassGravel(Holder<Biome> biome, int worldX, int worldZ, int salt, int threshold) {
      return biome.is(Biomes.PLAINS) && surfaceVariant(worldX, worldZ, salt) < threshold;
   }

   private static boolean isGravellyPalette(EarthChunkGenerator.SurfacePalette palette) {
      return palette.top().is(Blocks.GRAVEL);
   }

   /**
    * Clustered 0-99 roll for natural material mixes: thresholds keep their area
    * shares, but the materials form patches instead of per-block speckle.
    */
   private static int surfaceVariant(int worldX, int worldZ, int salt) {
      return SurfacePatchNoise.percent(worldX, worldZ, SURFACE_VARIANT_SALT ^ (long)salt * -7046029254386353131L);
   }

   private EarthChunkGenerator.SurfacePalette oceanFloorPalette(int worldX, int worldZ) {
      int roll = SurfacePatchNoise.percent(worldX, worldZ, OCEAN_FLOOR_SALT);
      if (roll < 10) {
         return EarthChunkGenerator.SurfacePalette.ocean(GRAVEL_STATE);
      } else {
         return roll < 15 ? EarthChunkGenerator.SurfacePalette.ocean(CLAY_STATE) : EarthChunkGenerator.SurfacePalette.ocean(SAND_STATE);
      }
   }

   private static BiomeGenerationSettings generationSettingsForBiome(Holder<Biome> biome, EarthGeneratorSettings settings) {
      boolean keepTrees = biome.is(Biomes.MANGROVE_SWAMP);
      int flags = geologyFlags(settings, keepTrees);
      EarthChunkGenerator.BiomeSettingsKey key = new EarthChunkGenerator.BiomeSettingsKey(biome, flags);
      return FILTERED_SETTINGS.computeIfAbsent(key, cached -> filterGenerationSettings(biome, settings, keepTrees));
   }

   private static BiomeGenerationSettings filterGenerationSettings(Holder<Biome> biome, EarthGeneratorSettings settings, boolean keepTrees) {
      BiomeGenerationSettings original = ((Biome)biome.value()).getGenerationSettings();
      PlainBuilder builder = new PlainBuilder();
      boolean keepBiomeDeepDarkFeatures = biome.is(Biomes.DEEP_DARK);

      MinecraftVersionCompat.copyCarvers(original, builder, carver -> shouldKeepCarver(carver, settings));

      List<HolderSet<PlacedFeature>> features = original.features();

      for (int step = 0; step < features.size(); step++) {
         for (Holder<PlacedFeature> feature : features.get(step)) {
            Holder<PlacedFeature> safeFeature = Objects.requireNonNull(feature, "feature");
            if ((keepTrees || !isTreeFeature((PlacedFeature)safeFeature.value()))
               && shouldKeepFeature(safeFeature, settings, keepBiomeDeepDarkFeatures)) {
               builder.addFeature(step, safeFeature);
            }
         }
      }

      return builder.build();
   }

   private static int geologyFlags(EarthGeneratorSettings settings, boolean keepTrees) {
      int flags = 0;
      if (settings.caveGeneration()) {
         flags |= 1;
      }

      if (settings.oreDistribution()) {
         flags |= 2;
      }

      if (settings.lavaPools()) {
         flags |= 4;
      }

      if (settings.deepDark()) {
         flags |= 8;
      }

      if (settings.geodes()) {
         flags |= 16;
      }

      if (keepTrees) {
         flags |= 32;
      }

      if (settings.suppressesUndergroundGenerationForTerrainShell()) {
         flags |= 64;
      }

      if (settings.geologicalStonePatches()) {
         flags |= 128;
      }

      return flags;
   }

   private static boolean shouldKeepCarver(Holder<?> carver, EarthGeneratorSettings settings) {
      return carver.unwrapKey().map(MinecraftVersionCompat::keyPath).map(path -> shouldKeepCarverId(path, settings)).orElse(true);
   }

   private static boolean shouldKeepCarverId(String path, EarthGeneratorSettings settings) {
      if (settings.suppressesUndergroundGenerationForTerrainShell()) {
         return false;
      }

      return settings.caveGeneration() || !path.equals("cave") && !path.equals("cave_extra_underground") && !path.equals("canyon");
   }

   private static boolean shouldKeepFeature(
      Holder<PlacedFeature> feature, EarthGeneratorSettings settings, boolean keepBiomeDeepDarkFeatures
   ) {
      UndergroundFeatureClassifier.Kind featureKind = UndergroundFeatureClassifier.classify(feature.value());
      return feature.unwrapKey()
         .map(MinecraftVersionCompat::keyPath)
         .map(path -> shouldKeepFeatureId(path, featureKind, settings, keepBiomeDeepDarkFeatures))
         .orElseGet(() -> shouldKeepFeatureId("", featureKind, settings, keepBiomeDeepDarkFeatures));
   }

   private static boolean shouldKeepFeatureId(
      String path,
      UndergroundFeatureClassifier.Kind featureKind,
      EarthGeneratorSettings settings,
      boolean keepBiomeDeepDarkFeatures
   ) {
      if (settings.suppressesUndergroundGenerationForTerrainShell() && isThinShellUndergroundFeatureId(path)) {
         return false;
      } else if (path.equals("freeze_top_layer") || path.equals("snow_and_freeze")) {
         return false;
      } else if (featureKind == UndergroundFeatureClassifier.Kind.GEOLOGICAL_STONE
         && !settings.geologicalStonePatches()) {
         return false;
      } else if (featureKind != UndergroundFeatureClassifier.Kind.GEOLOGICAL_STONE
         && !settings.oreDistribution()
         && (featureKind == UndergroundFeatureClassifier.Kind.MINEABLE_ORE || path.startsWith("ore_"))) {
         return false;
      } else if (!settings.geodes() && path.contains("geode")) {
         return false;
      } else if (settings.deepDark() || keepBiomeDeepDarkFeatures || !path.contains("sculk") && !path.contains("deep_dark")) {
         return settings.caveGeneration() || !path.contains("dripstone") && !path.startsWith("spring_water")
            ? settings.lavaPools() || !path.startsWith("lake_lava") && !path.startsWith("spring_lava")
            : false;
      } else {
         return false;
      }
   }

   private static boolean isThinShellUndergroundFeatureId(String path) {
      return path.startsWith("ore_")
         || path.contains("geode")
         || path.contains("sculk")
         || path.contains("deep_dark")
         || path.contains("dripstone")
         || path.startsWith("spring_")
         || path.startsWith("lake_lava")
         || path.startsWith("lake_water")
         || path.contains("lava")
         || path.contains("monster_room")
         || path.contains("fossil")
         || path.contains("underground")
         || path.contains("cave")
         || path.contains("amethyst");
   }

   private boolean isStructureSetEnabled(Holder<StructureSet> structureSet) {
      for (StructureSelectionEntry entry : ((StructureSet)structureSet.value()).structures()) {
         if (!this.isStructureEnabled(entry.structure())) {
            return false;
         }
      }

      return true;
   }

   private boolean isStructureEnabled(Holder<Structure> structure) {
      return structure.unwrapKey().map(MinecraftVersionCompat::keyPath).map(path -> this.isStructureEnabled(path)).orElse(true);
   }

   private boolean isStructureEnabled(String path) {
      if (this.settings.suppressesUndergroundGenerationForTerrainShell() && isThinShellUndergroundStructureId(path)) {
         return false;
      } else if (path.startsWith("village")) {
         return this.settings.addVillages();
      } else if (path.equals("stronghold")) {
         return this.settings.addStrongholds();
      } else if (path.startsWith("mineshaft")) {
         return this.settings.addMineshafts();
      } else if (path.equals("igloo")) {
         return this.settings.addIgloos();
      } else if (path.equals("ocean_monument") || path.equals("monument")) {
         return this.settings.addOceanMonuments();
      } else if (VanillaStructurePlacement.isWoodlandMansionPath(path)) {
         return this.settings.addWoodlandMansions();
      } else if (path.equals("desert_pyramid") || path.equals("desert_temple")) {
         return this.settings.addDesertTemples();
      } else if (path.equals("jungle_pyramid") || path.equals("jungle_temple")) {
         return this.settings.addJungleTemples();
      } else if (path.equals("pillager_outpost")) {
         return this.settings.addPillagerOutposts();
      } else if (path.startsWith("ruined_portal")) {
         return this.settings.addRuinedPortals();
      } else if (path.startsWith("shipwreck")) {
         return this.settings.addShipwrecks();
      } else if (path.startsWith("ocean_ruin")) {
         return this.settings.addOceanRuins();
      } else if (path.equals("buried_treasure")) {
         return this.settings.addBuriedTreasure();
      } else if (path.equals("swamp_hut") || path.equals("witch_hut")) {
         return this.settings.addWitchHuts();
      } else if (path.equals("ancient_city")) {
         return this.settings.addAncientCities();
      } else if (path.equals("trial_chambers")) {
         return this.settings.addTrialChambers();
      } else if (VanillaStructurePlacement.isAbandonedCampPath(path)) {
         return this.settings.addAbandonedCamps();
      } else {
         return path.startsWith("trail_ruins") ? this.settings.addTrailRuins() : true;
      }
   }

   private static boolean isThinShellUndergroundStructureId(String path) {
      return path.equals("stronghold")
         || path.startsWith("mineshaft")
         || path.equals("ancient_city")
         || path.equals("trial_chambers")
         || path.equals("buried_treasure");
   }

   private static List<TreeFeatureHandle> treeFeaturesForBiome(Holder<Biome> biome, boolean hugeRedMushrooms) {
      EarthChunkGenerator.TreeFeatureSettingsKey key = new EarthChunkGenerator.TreeFeatureSettingsKey(biome, hugeRedMushrooms);
      return TREE_FEATURES.computeIfAbsent(key, ignored -> {
         List<TreeFeatureHandle> result = new ArrayList<>();

         for (HolderSet<PlacedFeature> set : ((Biome)biome.value()).getGenerationSettings().features()) {
            for (Holder<PlacedFeature> feature : set) {
               PlacedFeature placed = (PlacedFeature)feature.value();
               if (!isTreeFeature(placed)) {
                  continue;
               }

               if (hugeRedMushrooms || !isHugeRedMushroomFeature(placed)) {
                  result.add(WorldgenApi.primaryFeature(placed));
               } else {
                  addNonRedTreeFeatures(result, placed);
               }
            }
         }

         return List.copyOf(result);
      });
   }

   private void retargetStrongholdStarts(RegistryAccess registryAccess, ChunkAccess chunk) {
      Registry<Structure> registry = MinecraftVersionCompat.registry(registryAccess, Registries.STRUCTURE);
      Structure stronghold = Objects.requireNonNull((Structure)MinecraftVersionCompat.registryValue(registry, BuiltinStructures.STRONGHOLD), "strongholdStructure");
      StructureStart start = chunk.getStartForStructure(stronghold);
      if (start != null && start.isValid()) {
         int chunkMinY = MinecraftVersionCompat.minY(chunk);
         int chunkMaxY = chunkMinY + chunk.getHeight() - 1;
         BoundingBox box = start.getBoundingBox();
         BlockPos center = box.getCenter();
         int centerX = center.getX();
         int centerY = center.getY();
         int centerZ = center.getZ();
         WaterSurfaceResolver.WaterColumnData column = this.resolveAuxWaterColumn(center.getX(), center.getZ());
         int terrainSurface = column.terrainSurface();
         boolean inOceanColumn = column.hasWater() && column.isOcean() && column.waterSurface() > terrainSurface;
         boolean tooShallow = centerY > terrainSurface - 20;
         if (VanillaStructurePlacement.shouldRetargetStronghold(this.settings.usesTerrainShell(), inOceanColumn, tooShallow)) {
            UndergroundStructureEnvelope envelope = this.sampleUndergroundStructureEnvelope(box);
            VanillaStructurePlacement.VerticalPlacementBounds bounds = VanillaStructurePlacement.verticalPlacementBounds(
               box.minY(), box.maxY(), envelope.lowestSurfaceY(), chunkMinY, chunkMaxY, 4, 8
            );
            if (!bounds.canFit()) {
               chunk.setStartForStructure(stronghold, StructureStart.INVALID_START);
            } else {
               long seed = seedFromCoords(centerX, 19, centerZ) ^ this.worldSeed ^ 8927292519556160640L;
               int targetDepth = 42 + seededRandomInt(seed, 17);
               int offsetY = Mth.clamp(
                  terrainSurface - targetDepth - centerY, bounds.minOffsetY(), bounds.maxOffsetY()
               );
               if (offsetY != 0) {
                  List<StructurePiece> movedPieces = new ArrayList<>(start.getPieces().size());

                  for (StructurePiece piece : start.getPieces()) {
                     piece.move(0, offsetY, 0);
                     movedPieces.add(piece);
                  }

                  StructureStart moved = new StructureStart(stronghold, start.getChunkPos(), start.getReferences(), new PiecesContainer(movedPieces));
                  chunk.setStartForStructure(stronghold, moved);
               }
            }
         }
      }
   }

   private void retargetMineshaftStarts(RegistryAccess registryAccess, ChunkAccess chunk) {
      Map<Structure, StructureStart> starts = chunk.getAllStarts();
      if (!starts.isEmpty()) {
         Registry<Structure> registry = MinecraftVersionCompat.registry(registryAccess, Registries.STRUCTURE);
         Structure normalMineshaft = Objects.requireNonNull((Structure)MinecraftVersionCompat.registryValue(registry, BuiltinStructures.MINESHAFT), "normalMineshaft");
         Structure mesaMineshaft = Objects.requireNonNull((Structure)MinecraftVersionCompat.registryValue(registry, BuiltinStructures.MINESHAFT_MESA), "mesaMineshaft");
         int chunkMinY = MinecraftVersionCompat.minY(chunk);
         int chunkMaxY = chunkMinY + chunk.getHeight() - 1;

         for (Entry<Structure, StructureStart> entry : starts.entrySet()) {
            Structure structure = Objects.requireNonNull(entry.getKey(), "structure");
            boolean mesaMineshaftStart = structure == mesaMineshaft;
            if (structure == normalMineshaft
               || (mesaMineshaftStart && VanillaStructurePlacement.shouldRetargetMesaMineshaft(this.settings.usesTerrainShell()))) {
               StructureStart start = entry.getValue();
               if (start != null && start.isValid()) {
                  BoundingBox box = start.getBoundingBox();
                  BlockPos center = box.getCenter();
                  int centerX = center.getX();
                  int centerY = center.getY();
                  int centerZ = center.getZ();
                  int surface = this.sampleSurfaceHeight(centerX, centerZ);
                  UndergroundStructureEnvelope envelope = this.sampleUndergroundStructureEnvelope(box);
                  VanillaStructurePlacement.VerticalPlacementBounds bounds = VanillaStructurePlacement.verticalPlacementBounds(
                     box.minY(),
                     box.maxY(),
                     envelope.lowestSurfaceY(),
                     chunkMinY,
                     chunkMaxY,
                     4,
                     VanillaStructurePlacement.mineshaftSurfaceClearance(mesaMineshaftStart)
                  );
                  if (!bounds.canFit()) {
                     chunk.setStartForStructure(structure, StructureStart.INVALID_START);
                  } else {
                     long seed = seedFromCoords(centerX, 14, centerZ) ^ this.worldSeed ^ 2115183054018638465L;
                     int targetDepth = VanillaStructurePlacement.mineshaftDepthBase(mesaMineshaftStart)
                        + seededRandomInt(seed, VanillaStructurePlacement.mineshaftDepthRange(mesaMineshaftStart));
                     int offsetY = Mth.clamp(
                        surface - targetDepth - centerY, bounds.minOffsetY(), bounds.maxOffsetY()
                     );
                     if (offsetY != 0) {
                        List<StructurePiece> movedPieces = new ArrayList<>(start.getPieces().size());

                        for (StructurePiece piece : start.getPieces()) {
                           piece.move(0, offsetY, 0);
                           movedPieces.add(piece);
                        }

                        StructureStart moved = new StructureStart(structure, start.getChunkPos(), start.getReferences(), new PiecesContainer(movedPieces));
                        chunk.setStartForStructure(structure, moved);
                     }
                  }
               }
            }
         }
      }
   }

   private void retargetAncientCityStarts(RegistryAccess registryAccess, ChunkAccess chunk) {
      Map<Structure, StructureStart> starts = chunk.getAllStarts();
      if (!starts.isEmpty()) {
         Registry<Structure> registry = MinecraftVersionCompat.registry(registryAccess, Registries.STRUCTURE);
         Structure ancientCity = Objects.requireNonNull((Structure)MinecraftVersionCompat.registryValue(registry, BuiltinStructures.ANCIENT_CITY), "ancientCity");
         int chunkMinY = MinecraftVersionCompat.minY(chunk);
         int chunkMaxY = chunkMinY + chunk.getHeight() - 1;

         for (Entry<Structure, StructureStart> entry : starts.entrySet()) {
            Structure structure = Objects.requireNonNull(entry.getKey(), "structure");
            if (structure == ancientCity) {
               StructureStart start = entry.getValue();
               if (start != null && start.isValid()) {
                  BoundingBox box = start.getBoundingBox();
                  BlockPos center = box.getCenter();
                  int centerX = center.getX();
                  int centerY = center.getY();
                  int centerZ = center.getZ();
                  int terrainSurface = this.resolveAuxWaterColumn(centerX, centerZ).terrainSurface();
                  UndergroundStructureEnvelope envelope = this.sampleUndergroundStructureEnvelope(box);
                  int requiredTopY = envelope.lowestSurfaceY() - 8;
                  if (VanillaStructurePlacement.shouldRetargetBuriedStructure(this.settings.usesTerrainShell(), box.maxY(), requiredTopY)) {
                     VanillaStructurePlacement.VerticalPlacementBounds bounds = VanillaStructurePlacement.verticalPlacementBounds(
                        box.minY(), box.maxY(), envelope.lowestSurfaceY(), chunkMinY, chunkMaxY, 4, 8
                     );
                     if (!bounds.canFit()) {
                        chunk.setStartForStructure(structure, StructureStart.INVALID_START);
                     } else {
                        long seed = seedFromCoords(centerX, 23, centerZ) ^ this.worldSeed ^ 7099176517079344131L;
                        int targetDepth = 92 + seededRandomInt(seed, 29);
                        int offsetY = Mth.clamp(terrainSurface - targetDepth - centerY, bounds.minOffsetY(), bounds.maxOffsetY());
                        if (offsetY != 0) {
                           List<StructurePiece> movedPieces = new ArrayList<>(start.getPieces().size());

                           for (StructurePiece piece : start.getPieces()) {
                              piece.move(0, offsetY, 0);
                              movedPieces.add(piece);
                           }

                           StructureStart moved = new StructureStart(structure, start.getChunkPos(), start.getReferences(), new PiecesContainer(movedPieces));
                           chunk.setStartForStructure(structure, moved);
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private void retargetTrialChamberStarts(RegistryAccess registryAccess, ChunkAccess chunk) {
      Map<Structure, StructureStart> starts = chunk.getAllStarts();
      if (!starts.isEmpty()) {
         Registry<Structure> registry = MinecraftVersionCompat.registry(registryAccess, Registries.STRUCTURE);
         Structure trialChambers = trialChambers(registry);
         if (trialChambers == null) {
            return;
         }
         int chunkMinY = MinecraftVersionCompat.minY(chunk);
         int chunkMaxY = chunkMinY + chunk.getHeight() - 1;

         for (Entry<Structure, StructureStart> entry : starts.entrySet()) {
            Structure structure = Objects.requireNonNull(entry.getKey(), "structure");
            if (structure == trialChambers) {
               StructureStart start = entry.getValue();
               if (start != null && start.isValid()) {
                  BoundingBox box = start.getBoundingBox();
                  BlockPos center = box.getCenter();
                  int centerX = center.getX();
                  int centerY = center.getY();
                  int centerZ = center.getZ();
                  WaterSurfaceResolver.WaterColumnData column = this.resolveAuxWaterColumn(centerX, centerZ);
                  int terrainSurface = column.terrainSurface();
                  UndergroundStructureEnvelope envelope = this.sampleUndergroundStructureEnvelope(box);
                  int requiredTopY = envelope.lowestSurfaceY() - 8;
                  if (VanillaStructurePlacement.shouldRetargetBuriedStructure(this.settings.usesTerrainShell(), box.maxY(), requiredTopY)) {
                     VanillaStructurePlacement.VerticalPlacementBounds bounds = VanillaStructurePlacement.verticalPlacementBounds(
                        box.minY(), box.maxY(), envelope.lowestSurfaceY(), chunkMinY, chunkMaxY, 4, 8
                     );
                     if (!bounds.canFit()) {
                        chunk.setStartForStructure(structure, StructureStart.INVALID_START);
                     } else {
                        long seed = seedFromCoords(centerX, 17, centerZ) ^ this.worldSeed ^ 4235900233171027605L;
                        int targetDepth = 72 + seededRandomInt(seed, 25);
                        int offsetY = Mth.clamp(terrainSurface - targetDepth - centerY, bounds.minOffsetY(), bounds.maxOffsetY());
                        if (offsetY != 0) {
                           List<StructurePiece> movedPieces = new ArrayList<>(start.getPieces().size());

                           for (StructurePiece piece : start.getPieces()) {
                              piece.move(0, offsetY, 0);
                              movedPieces.add(piece);
                           }

                           StructureStart moved = new StructureStart(structure, start.getChunkPos(), start.getReferences(), new PiecesContainer(movedPieces));
                           chunk.setStartForStructure(structure, moved);
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private void adjustOceanMonumentStarts(RegistryAccess registryAccess, ChunkAccess chunk) {
      Registry<Structure> registry = MinecraftVersionCompat.registry(registryAccess, Registries.STRUCTURE);
      Structure monument = Objects.requireNonNull((Structure)MinecraftVersionCompat.registryValue(registry, BuiltinStructures.OCEAN_MONUMENT), "oceanMonument");
      StructureStart start = chunk.getStartForStructure(monument);
      if (start != null && start.isValid()) {
         BoundingBox box = start.getBoundingBox();
         if (!this.isOceanMonumentStartViable(box)) {
            chunk.setStartForStructure(monument, StructureStart.INVALID_START);
         } else {
            int targetTopY = this.seaLevel - OCEAN_MONUMENT_TOP_BELOW_SEA;
            int desiredMinY = targetTopY - box.getYSpan() + 1;
            int worldMaxY = this.minY + this.height - 1;
            int minAllowedOffset = this.minY + 1 - box.minY();
            int maxAllowedOffset = worldMaxY - box.maxY();
            int offsetY = Mth.clamp(desiredMinY - box.minY(), minAllowedOffset, maxAllowedOffset);
            if (box.maxY() + offsetY > targetTopY) {
               chunk.setStartForStructure(monument, StructureStart.INVALID_START);
            } else if (offsetY != 0) {
               List<StructurePiece> movedPieces = new ArrayList<>(start.getPieces().size());

               for (StructurePiece piece : start.getPieces()) {
                  piece.move(0, offsetY, 0);
                  movedPieces.add(piece);
               }

               StructureStart moved = new StructureStart(monument, start.getChunkPos(), start.getReferences(), new PiecesContainer(movedPieces));
               chunk.setStartForStructure(monument, moved);
            }
         }
      }
   }

   private boolean isOceanMonumentStartViable(BoundingBox box) {
      if (!this.settings.enableWater()) {
         return true;
      } else {
         int minX = box.minX() - OCEAN_MONUMENT_MARGIN;
         int maxX = box.maxX() + OCEAN_MONUMENT_MARGIN;
         int minZ = box.minZ() - OCEAN_MONUMENT_MARGIN;
         int maxZ = box.maxZ() + OCEAN_MONUMENT_MARGIN;
         int coreMinX = box.minX() + OCEAN_MONUMENT_CORE_INSET;
         int coreMaxX = box.maxX() - OCEAN_MONUMENT_CORE_INSET;
         int coreMinZ = box.minZ() + OCEAN_MONUMENT_CORE_INSET;
         int coreMaxZ = box.maxZ() - OCEAN_MONUMENT_CORE_INSET;
         int requiredCoreDepth = Math.max(OCEAN_MONUMENT_MIN_DEEP_OCEAN_DEPTH + 7, box.getYSpan() - 4);
         int totalSamples = 0;
         int oceanSamples = 0;
         int deepOceanSamples = 0;
         int coreSamples = 0;

         for (int sampleZ = minZ; ; sampleZ = Math.min(sampleZ + OCEAN_MONUMENT_SAMPLE_STEP, maxZ)) {
            for (int sampleX = minX; ; sampleX = Math.min(sampleX + OCEAN_MONUMENT_SAMPLE_STEP, maxX)) {
               WaterSurfaceResolver.WaterColumnData column = this.resolveOceanMonumentWaterColumn(sampleX, sampleZ);
               int waterDepth = oceanMonumentWaterDepth(column);
               boolean oceanColumn = waterDepth > 0;
               totalSamples++;
               if (oceanColumn) {
                  oceanSamples++;
               }

               if (waterDepth >= OCEAN_MONUMENT_MIN_DEEP_OCEAN_DEPTH) {
                  deepOceanSamples++;
               }

               if (sampleX >= coreMinX && sampleX <= coreMaxX && sampleZ >= coreMinZ && sampleZ <= coreMaxZ) {
                  coreSamples++;
                  if (!oceanColumn || waterDepth < requiredCoreDepth) {
                     return false;
                  }
               }

               if (sampleX == maxX) {
                  break;
               }
            }

            if (sampleZ == maxZ) {
               return coreSamples > 0
                  && (double)oceanSamples / (double)totalSamples >= OCEAN_MONUMENT_MIN_OCEAN_RATIO
                  && (double)deepOceanSamples / (double)totalSamples >= OCEAN_MONUMENT_MIN_DEEP_OCEAN_RATIO;
            }
         }
      }
   }

   private WaterSurfaceResolver.WaterColumnData resolveOceanMonumentWaterColumn(int worldX, int worldZ) {
      int coverClass = this.sampleCoverClass(worldX, worldZ);
      WaterSurfaceResolver.WaterColumnData column = this.resolveOsmWaterColumn(worldX, worldZ, coverClass, true);
      return this.normalizeResolvedWaterColumn(worldX, worldZ, coverClass, this.minY, this.minY + this.height - 1, column);
   }

   private static int oceanMonumentWaterDepth(WaterSurfaceResolver.WaterColumnData column) {
      return column.hasWater() && column.isOcean() && column.waterSurface() > column.terrainSurface() ? column.waterSurface() - column.terrainSurface() : 0;
   }

   private static boolean isTreeFeature(PlacedFeature feature) {
      return WorldgenApi.features(feature).anyMatch(TreeFeatureHandle::isTree);
   }

   private static boolean isHugeRedMushroomFeature(PlacedFeature feature) {
      return WorldgenApi.features(feature).anyMatch(TreeFeatureHandle::isHugeRedMushroom);
   }

   private static void addNonRedTreeFeatures(List<TreeFeatureHandle> result, PlacedFeature placed) {
      WorldgenApi.features(placed)
         .filter(feature -> feature.isTree() && !feature.isHugeRedMushroom())
         .forEach(result::add);
   }

   private boolean shouldRetainSurfaceSnow(int surfaceCoverClass, int worldX, int worldZ) {
      boolean snowLikeTerrain = this.isAntarcticSnowTerrain(surfaceCoverClass, worldZ);
      return this.shouldRetainPersistentSnowAt(
         worldX, worldZ, surfaceCoverClass, snowLikeTerrain
      );
   }

   private void applySnowCover(
      ChunkAccess chunk,
      MutableBlockPos cursor,
      int worldX,
      int worldZ,
      int surface,
      int minY,
      boolean fullSlopeCoverage,
      boolean glacierHollow
   ) {
      SurfaceMaterialDepthPolicy.SnowColumn column = SurfaceMaterialDepthPolicy.snowColumn(
         worldX, worldZ, this.worldSeed, fullSlopeCoverage
      );
      BlockState snow = snowCoverState(column, glacierHollow, worldX, worldZ);
      for (int depth = 0; depth < column.depth(); depth++) {
         int y = surface - depth;
         if (y < minY) {
            break;
         }

         cursor.set(worldX, y, worldZ);
         setChunkBlockStateDiscardingBlockEntity(chunk, cursor, snow);
      }
   }

   private void applySnowCover(
      EarthChunkGenerator.ChunkSectionWriter writer,
      int localX,
      int localZ,
      int worldX,
      int worldZ,
      int surface,
      int minY,
      boolean fullSlopeCoverage,
      boolean glacierHollow
   ) {
      SurfaceMaterialDepthPolicy.SnowColumn column = SurfaceMaterialDepthPolicy.snowColumn(
         worldX, worldZ, this.worldSeed, fullSlopeCoverage
      );
      BlockState snow = snowCoverState(column, glacierHollow, worldX, worldZ);
      int bottom = Math.max(minY, surface - column.depth() + 1);
      writer.fillColumnConstant(localX, localZ, bottom, surface, snow);
   }

   private static BlockState snowCoverState(
      SurfaceMaterialDepthPolicy.SnowColumn column, boolean glacierHollow, int worldX, int worldZ
   ) {
      if (glacierHollow) {
         // Matches the LOD glacier palette, so distant and near glaciers show the same ice.
         return mountainMaterialState(MountainSurfaceRules.glacierSurfaceMaterial(worldX, worldZ));
      }

      return column.powderSnow() ? POWDER_SNOW_STATE : SNOW_BLOCK_STATE;
   }

   private static BlockState mountainMaterialState(int material) {
      return switch (material) {
         case MountainSurfaceRules.MATERIAL_ANDESITE -> ANDESITE_STATE;
         case MountainSurfaceRules.MATERIAL_GRAVEL -> GRAVEL_STATE;
         case MountainSurfaceRules.MATERIAL_COBBLESTONE -> COBBLESTONE_STATE;
         case MountainSurfaceRules.MATERIAL_DIORITE -> DIORITE_STATE;
         case MountainSurfaceRules.MATERIAL_PACKED_ICE -> PACKED_ICE_STATE;
         case MountainSurfaceRules.MATERIAL_BLUE_ICE -> BLUE_ICE_STATE;
         case MountainSurfaceRules.MATERIAL_SNOW -> SNOW_BLOCK_STATE;
         default -> STONE_STATE;
      };
   }

   public void applyRealtimeSnowCover(WorldGenLevel level, ChunkAccess chunk) {
      if (TellusRealtimeState.isHistoricalSnowEnabled()
         || TellusRealtimeState.isWeatherEnabled() && TellusRealtimeState.precipitationMode() == TellusRealtimeState.PrecipitationMode.SNOW) {
         int updateFlags = level instanceof ServerLevel ? 2 : 260;
         ChunkPos pos = chunk.getPos();
         int chunkMinX = pos.getMinBlockX();
         int chunkMinZ = pos.getMinBlockZ();
         int minY = MinecraftVersionCompat.minY(chunk);
         int maxY = minY + chunk.getHeight();
         MutableBlockPos cursor = new MutableBlockPos();
         BlockState snowLayer = SNOW_LAYER_STATE;

         for (int localX = 0; localX < 16; localX++) {
            int worldX = chunkMinX + localX;

            for (int localZ = 0; localZ < 16; localZ++) {
               int worldZ = chunkMinZ + localZ;
               if (TellusRealtimeState.shouldApplySnow(worldX, worldZ)) {
                  int surface = level.getHeight(Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ);
                  if (surface >= minY && surface + 1 < maxY) {
                     if (this.shouldPlaceSnowAt(worldX, worldZ)) {
                        cursor.set(worldX, surface, worldZ);
                        BlockState surfaceState = level.getBlockState(cursor);
                        if (surfaceState.getFluidState().isEmpty()) {
                           BlockPos above = cursor.above();
                           if (level.getBlockState(above).isAir() && snowLayer.canSurvive(level, above)) {
                              level.setBlock(above, snowLayer, updateFlags);
                              if (surfaceState.hasProperty(BlockStateProperties.SNOWY) && !(Boolean)surfaceState.getValue(BlockStateProperties.SNOWY)) {
                                 BlockState snowySurface = Objects.requireNonNull(
                                    (BlockState)surfaceState.setValue(BlockStateProperties.SNOWY, Boolean.TRUE), "snowySurface"
                                 );
                                 level.setBlock(cursor, snowySurface, updateFlags);
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private void spawnAxolotlsInLushPonds(WorldGenLevel level, ChunkAccess chunk) {
      ServerLevel serverLevel = level.getLevel();
      if (serverLevel.getServer().isSameThread()) {
         ChunkPos pos = chunk.getPos();
         long seed = seedFromCoords(MinecraftVersionCompat.chunkX(pos), 11, MinecraftVersionCompat.chunkZ(pos))
            ^ this.worldSeed
            ^ 8943931030581793800L;
         RandomSource random = RandomSource.create(seed);
         if (!(random.nextFloat() > AXOLOTL_CHUNK_CHANCE)) {
            int chunkMinX = pos.getMinBlockX();
            int chunkMinZ = pos.getMinBlockZ();
            int minY = Math.max(this.minY + 4, MinecraftVersionCompat.minY(chunk) + 4);
            int spawned = 0;
            int attempts = 6 + random.nextInt(6);
            MutableBlockPos cursor = new MutableBlockPos();

            for (int attempt = 0; attempt < attempts; attempt++) {
               int worldX = chunkMinX + random.nextInt(CHUNK_SIDE);
               int worldZ = chunkMinZ + random.nextInt(CHUNK_SIDE);
               int surface = level.getHeight(Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ);
               int maxY = Math.min(surface - 4, this.seaLevel - 1);
               if (maxY > minY) {
                  for (int y = maxY; y >= minY; y--) {
                     cursor.set(worldX, y, worldZ);
                     BlockState water = level.getBlockState(cursor);
                     if (water.getFluidState().is(Fluids.WATER)) {
                        cursor.set(worldX, y - 1, worldZ);
                        if (level.getBlockState(cursor).is(Blocks.CLAY)) {
                           cursor.set(worldX, y + 1, worldZ);
                           if (level.getBlockState(cursor).isAir()) {
                              cursor.set(worldX, y, worldZ);
                              if (level.getBiome(cursor).is(Biomes.LUSH_CAVES) && !(random.nextFloat() > AXOLOTL_POND_CHANCE)) {
                                 Axolotl axolotl = MinecraftVersionCompat.createAxolotl(serverLevel, cursor.immutable());
                                 if (axolotl == null) {
                                    return;
                                 }

                                 axolotl.setPersistenceRequired();
                                 MinecraftVersionCompat.snapTo(axolotl, worldX + 0.5, y + 0.1, worldZ + 0.5, random.nextFloat() * 360.0F, 0.0F);
                                 if (serverLevel.addFreshEntity(axolotl)) {
                                    spawned++;
                                 }
                              }
                              break;
                           }
                        }
                     }
                  }

                  if (spawned >= MAX_AXOLOTLS_PER_CHUNK) {
                     return;
                  }
               }
            }
         }
      }
   }

   private record BiomeSettingsKey(Holder<Biome> biome, int flags) {
   }

   private record TreeFeatureSettingsKey(Holder<Biome> biome, boolean hugeRedMushrooms) {
   }

   private record ColumnHeights(int terrainSurface, int waterSurface, boolean hasWater) {
   }

   private static final class FilteredStructureLookup implements HolderLookup<StructureSet> {
      private final HolderLookup<StructureSet> delegate;
      private final Predicate<Holder<StructureSet>> predicate;

      private FilteredStructureLookup(HolderLookup<StructureSet> delegate, Predicate<Holder<StructureSet>> predicate) {
         this.delegate = delegate;
         this.predicate = predicate;
      }


      public Stream<Reference<StructureSet>> listElements() {
         return Objects.requireNonNull(this.delegate.listElements().filter(this.predicate), "listElements");
      }


      public Stream<Named<StructureSet>> listTags() {
         return Objects.requireNonNull(this.delegate.listTags(), "listTags");
      }


      public Optional<Reference<StructureSet>> get( ResourceKey<StructureSet> key) {
         return Objects.requireNonNull(this.delegate.get(key), "getStructureSet");
      }


      public Optional<Named<StructureSet>> get( TagKey<StructureSet> tag) {
         return Objects.requireNonNull(this.delegate.get(tag), "getStructureSetTag");
      }
   }

   public record LodSurface( BlockState top,  BlockState filler) {
   }

   public interface LodSurfaceProfiler {
      void addPhase(String phase, long nanos);
   }

   public interface LodShorelineCache {
      boolean definitelyDry();

      String mode();

      WaterSurfaceResolver.WaterInfo resolveFastWaterInfo(int worldX, int worldZ);
   }

   public interface LodSharedTerrainCache {
      String mode();

      int sampleCoverClass(int worldX, int worldZ);

      int sampleVisualCoverClass(int worldX, int worldZ);

      EarthChunkGenerator.LodShorelineCache shorelineCache();

      EarthChunkGenerator.LodMountainTransitionCache mountainTransitionCache();
   }

   public interface LodMountainTransitionCache {
      String mode();

      double previewResolutionMeters();

      float sampleVegetationWeight(int worldX, int worldZ, int heightAboveSea);

      boolean hasSnowSource(int worldX, int worldZ);
   }

   private final class DenseLodShorelineCache implements EarthChunkGenerator.LodShorelineCache {
      private final int minX;
      private final int maxX;
      private final int minZ;
      private final int maxZ;
      private final int width;
      private final WaterSurfaceResolver.WaterInfo[] waterInfos;
      private final boolean hasWater;
      private final double previewResolutionMeters;

      private DenseLodShorelineCache(
         int minX,
         int maxX,
         int minZ,
         int maxZ,
         int width,
         WaterSurfaceResolver.WaterInfo[] waterInfos,
         boolean hasWater,
         double previewResolutionMeters
      ) {
         this.minX = minX;
         this.maxX = maxX;
         this.minZ = minZ;
         this.maxZ = maxZ;
         this.width = width;
         this.waterInfos = waterInfos;
         this.hasWater = hasWater;
         this.previewResolutionMeters = previewResolutionMeters;
      }

      @Override
      public boolean definitelyDry() {
         return !this.hasWater;
      }

      @Override
      public String mode() {
         return this.hasWater ? "dense" : "dry";
      }

      @Override
      public WaterSurfaceResolver.WaterInfo resolveFastWaterInfo(int worldX, int worldZ) {
         if (worldX >= this.minX && worldX <= this.maxX && worldZ >= this.minZ && worldZ <= this.maxZ) {
            int localX = worldX - this.minX;
            int localZ = worldZ - this.minZ;
            return this.waterInfos[localZ * this.width + localX];
         } else {
            return EarthChunkGenerator.this.resolveFastWaterInfoForShoreline(worldX, worldZ, this.previewResolutionMeters);
         }
      }
   }

   private final class CellLodShorelineCache implements EarthChunkGenerator.LodShorelineCache {
      private final int cellMinX;
      private final int cellMinZ;
      private final int cellSize;
      private final int columns;
      private final int rows;
      private final WaterSurfaceResolver.WaterInfo[] waterInfos;
      private final boolean hasWater;
      private final double previewResolutionMeters;

      private CellLodShorelineCache(
         int originX,
         int originZ,
         int cellSize,
         int columns,
         int rows,
         WaterSurfaceResolver.WaterInfo[] waterInfos,
         boolean hasWater,
         double previewResolutionMeters
      ) {
         // The origin is the first cell's centre sample; its cell starts half a cell before it.
         this.cellMinX = originX - (cellSize >> 1);
         this.cellMinZ = originZ - (cellSize >> 1);
         this.cellSize = cellSize;
         this.columns = columns;
         this.rows = rows;
         this.waterInfos = waterInfos;
         this.hasWater = hasWater;
         this.previewResolutionMeters = previewResolutionMeters;
      }

      @Override
      public boolean definitelyDry() {
         return !this.hasWater;
      }

      @Override
      public String mode() {
         return this.hasWater ? "cells" : "dry";
      }

      @Override
      public WaterSurfaceResolver.WaterInfo resolveFastWaterInfo(int worldX, int worldZ) {
         int column = Math.floorDiv(worldX - this.cellMinX, this.cellSize);
         int row = Math.floorDiv(worldZ - this.cellMinZ, this.cellSize);
         if (column >= 0 && column < this.columns && row >= 0 && row < this.rows) {
            return this.waterInfos[row * this.columns + column];
         } else {
            return EarthChunkGenerator.this.resolveFastWaterInfoForShoreline(worldX, worldZ, this.previewResolutionMeters);
         }
      }
   }

   private final class SparseLodShorelineCache implements EarthChunkGenerator.LodShorelineCache {
      private final double previewResolutionMeters;
      private final Long2ObjectOpenHashMap<WaterSurfaceResolver.WaterInfo> waterInfoCache = new Long2ObjectOpenHashMap<>();

      private SparseLodShorelineCache(double previewResolutionMeters) {
         this.previewResolutionMeters = previewResolutionMeters;
      }

      @Override
      public boolean definitelyDry() {
         return false;
      }

      @Override
      public String mode() {
         return "sparse";
      }

      @Override
      public WaterSurfaceResolver.WaterInfo resolveFastWaterInfo(int worldX, int worldZ) {
         long key = BlockPos.asLong(worldX, 0, worldZ);
         WaterSurfaceResolver.WaterInfo cached = this.waterInfoCache.get(key);
         if (cached != null) {
            return cached;
         } else {
            WaterSurfaceResolver.WaterInfo resolved = EarthChunkGenerator.this.resolveFastWaterInfoForShoreline(
               worldX, worldZ, this.previewResolutionMeters
            );
            this.waterInfoCache.put(key, resolved);
            return resolved;
         }
      }
   }

   private final class DenseLodMountainTransitionCache implements EarthChunkGenerator.LodMountainTransitionCache {
      private final int minX;
      private final int maxX;
      private final int minZ;
      private final int maxZ;
      private final int width;
      private final byte[] surfaceCoverClasses;
      private final double previewResolutionMeters;

      private DenseLodMountainTransitionCache(
         int minX, int maxX, int minZ, int maxZ, int width, byte[] surfaceCoverClasses, double previewResolutionMeters
      ) {
         this.minX = minX;
         this.maxX = maxX;
         this.minZ = minZ;
         this.maxZ = maxZ;
         this.width = width;
         this.surfaceCoverClasses = surfaceCoverClasses;
         this.previewResolutionMeters = previewResolutionMeters;
      }

      @Override
      public String mode() {
         return "dense";
      }

      @Override
      public double previewResolutionMeters() {
         return this.previewResolutionMeters;
      }

      @Override
      public float sampleVegetationWeight(int worldX, int worldZ, int heightAboveSea) {
         if (worldX >= this.minX && worldX <= this.maxX && worldZ >= this.minZ && worldZ <= this.maxZ) {
            int localX = worldX - this.minX;
            int localZ = worldZ - this.minZ;
            int surfaceCoverClass = Byte.toUnsignedInt(this.surfaceCoverClasses[localZ * this.width + localX]);
            return MountainSurfaceRules.vegetationTransitionWeightForSurfaceCoverClass(surfaceCoverClass, heightAboveSea);
         } else {
            return EarthChunkGenerator.this.sampleMountainVegetationWeightUncached(worldX, worldZ, heightAboveSea, this.previewResolutionMeters);
         }
      }

      @Override
      public boolean hasSnowSource(int worldX, int worldZ) {
         if (worldX >= this.minX && worldX <= this.maxX && worldZ >= this.minZ && worldZ <= this.maxZ) {
            int localX = worldX - this.minX;
            int localZ = worldZ - this.minZ;
            int surfaceCoverClass = Byte.toUnsignedInt(this.surfaceCoverClasses[localZ * this.width + localX]);
            return MountainSurfaceRules.hasSnowSource(
               surfaceCoverClass,
               EarthChunkGenerator.this.isAntarcticSnowTerrain(surfaceCoverClass, worldZ)
            );
         } else {
            return EarthChunkGenerator.this.hasPersistentSnowSourceAtUncached(worldX, worldZ, this.previewResolutionMeters);
         }
      }
   }

   private final class LazyLodMountainTransitionCache implements EarthChunkGenerator.LodMountainTransitionCache {
      private final double previewResolutionMeters;

      private LazyLodMountainTransitionCache(double previewResolutionMeters) {
         this.previewResolutionMeters = previewResolutionMeters;
      }

      @Override
      public String mode() {
         return "lazy";
      }

      @Override
      public double previewResolutionMeters() {
         return this.previewResolutionMeters;
      }

      @Override
      public float sampleVegetationWeight(int worldX, int worldZ, int heightAboveSea) {
         return EarthChunkGenerator.this.sampleMountainVegetationWeightUncached(
            worldX, worldZ, heightAboveSea, this.previewResolutionMeters
         );
      }

      @Override
      public boolean hasSnowSource(int worldX, int worldZ) {
         return EarthChunkGenerator.this.hasPersistentSnowSourceAtUncached(worldX, worldZ, this.previewResolutionMeters);
      }
   }

   private final class DenseLodSharedTerrainCache implements EarthChunkGenerator.LodSharedTerrainCache {
      private final int minX;
      private final int maxX;
      private final int minZ;
      private final int maxZ;
      private final int width;
      private final byte[] rawCoverClasses;
      private final byte[] visualCoverClasses;
      private final EarthChunkGenerator.DenseLodShorelineCache shorelineCache;
      private final EarthChunkGenerator.DenseLodMountainTransitionCache mountainTransitionCache;
      private final double previewResolutionMeters;

      private DenseLodSharedTerrainCache(
         int minX,
         int maxX,
         int minZ,
         int maxZ,
         int width,
         byte[] rawCoverClasses,
         byte[] visualCoverClasses,
         EarthChunkGenerator.DenseLodShorelineCache shorelineCache,
         EarthChunkGenerator.DenseLodMountainTransitionCache mountainTransitionCache,
         double previewResolutionMeters
      ) {
         this.minX = minX;
         this.maxX = maxX;
         this.minZ = minZ;
         this.maxZ = maxZ;
         this.width = width;
         this.rawCoverClasses = rawCoverClasses;
         this.visualCoverClasses = visualCoverClasses;
         this.shorelineCache = shorelineCache;
         this.mountainTransitionCache = mountainTransitionCache;
         this.previewResolutionMeters = previewResolutionMeters;
      }

      @Override
      public String mode() {
         return "dense";
      }

      @Override
      public int sampleCoverClass(int worldX, int worldZ) {
         if (this.contains(worldX, worldZ)) {
            int localX = worldX - this.minX;
            int localZ = worldZ - this.minZ;
            return Byte.toUnsignedInt(this.rawCoverClasses[localZ * this.width + localX]);
         } else {
            return EarthChunkGenerator.this.sampleCoverClass(worldX, worldZ, this.previewResolutionMeters);
         }
      }

      @Override
      public int sampleVisualCoverClass(int worldX, int worldZ) {
         if (this.contains(worldX, worldZ)) {
            int localX = worldX - this.minX;
            int localZ = worldZ - this.minZ;
            return Byte.toUnsignedInt(this.visualCoverClasses[localZ * this.width + localX]);
         } else {
            int rawCoverClass = EarthChunkGenerator.this.sampleCoverClass(worldX, worldZ, this.previewResolutionMeters);
            return EarthChunkGenerator.this.sampleVisualCoverClass(worldX, worldZ, rawCoverClass, this.previewResolutionMeters);
         }
      }

      @Override
      public EarthChunkGenerator.LodShorelineCache shorelineCache() {
         return this.shorelineCache;
      }

      @Override
      public EarthChunkGenerator.LodMountainTransitionCache mountainTransitionCache() {
         return this.mountainTransitionCache;
      }

      private boolean contains(int worldX, int worldZ) {
         return worldX >= this.minX && worldX <= this.maxX && worldZ >= this.minZ && worldZ <= this.maxZ;
      }
   }

   private static final class OsmOverlayScratch {
      private final Long2ObjectOpenHashMap<EarthChunkGenerator.RoadColumnSample> edgeColumnCache = new Long2ObjectOpenHashMap<>();
      private byte[] resolvedClass = new byte[CHUNK_AREA];
      private byte[] resolvedMode = new byte[CHUNK_AREA];
      private byte[] resolvedStyle = new byte[CHUNK_AREA];
      private boolean[] resolvedMarking = new boolean[CHUNK_AREA];
      private int[] resolvedDeckY = new int[CHUNK_AREA];
      private boolean[] resolvedTunnelCarve = new boolean[CHUNK_AREA];
      private boolean[] blockedByHigherClass = new boolean[CHUNK_AREA];
      private boolean[] bridgeOverlayPresent = new boolean[CHUNK_AREA];
      private int[] bridgeOverlayDeckY = new int[CHUNK_AREA];
      private byte[] bridgeOverlayClass = new byte[CHUNK_AREA];
      private byte[] bridgeOverlayStyle = new byte[CHUNK_AREA];
      private boolean[] bridgeOverlayMarking = new boolean[CHUNK_AREA];
      private boolean[] bridgeSupportShaftPresent = new boolean[CHUNK_AREA];
      private int[] bridgeSupportShaftBottomY = new int[CHUNK_AREA];
      private int[] bridgeSupportShaftTopY = new int[CHUNK_AREA];
      private boolean[] bridgeSupportCapPresent = new boolean[CHUNK_AREA];
      private int[] bridgeSupportCapBottomY = new int[CHUNK_AREA];
      private int[] bridgeSupportCapTopY = new int[CHUNK_AREA];
      private boolean[] candidatePresent = new boolean[CHUNK_AREA];
      private int[] candidateDeckY = new int[CHUNK_AREA];
      private byte[] candidateMode = new byte[CHUNK_AREA];
      private byte[] candidateStyle = new byte[CHUNK_AREA];
      private boolean[] candidateMarking = new boolean[CHUNK_AREA];
      private boolean[] candidateTunnelCarve = new boolean[CHUNK_AREA];
      private boolean[] bridgeCandidatePresent = new boolean[CHUNK_AREA];
      private int[] bridgeCandidateDeckY = new int[CHUNK_AREA];
      private byte[] bridgeCandidateStyle = new byte[CHUNK_AREA];
      private boolean[] bridgeCandidateMarking = new boolean[CHUNK_AREA];
      private int[] placed = new int[CHUNK_AREA];
      private final byte[] chunkRoadClass = new byte[CHUNK_AREA];
      private final byte[] chunkRoadMode = new byte[CHUNK_AREA];
      private final byte[] chunkRoadStyle = new byte[CHUNK_AREA];
      private final boolean[] chunkRoadMarking = new boolean[CHUNK_AREA];
      private final int[] chunkRoadDeckY = new int[CHUNK_AREA];
      private final boolean[] chunkTunnelNeedsCarve = new boolean[CHUNK_AREA];
      private final boolean[] tunnelCarveMask = new boolean[CHUNK_AREA];
      private final int[] tunnelCarveDeckY = new int[CHUNK_AREA];
      private final int[] classWidths = new int[4];

      private void ensureRoadExtCapacity(int extArea) {
         if (this.resolvedClass.length < extArea) {
            this.resolvedClass = new byte[extArea];
            this.resolvedMode = new byte[extArea];
            this.resolvedStyle = new byte[extArea];
            this.resolvedMarking = new boolean[extArea];
            this.resolvedDeckY = new int[extArea];
            this.resolvedTunnelCarve = new boolean[extArea];
            this.blockedByHigherClass = new boolean[extArea];
            this.bridgeOverlayPresent = new boolean[extArea];
            this.bridgeOverlayDeckY = new int[extArea];
            this.bridgeOverlayClass = new byte[extArea];
            this.bridgeOverlayStyle = new byte[extArea];
            this.bridgeOverlayMarking = new boolean[extArea];
            this.bridgeSupportShaftPresent = new boolean[extArea];
            this.bridgeSupportShaftBottomY = new int[extArea];
            this.bridgeSupportShaftTopY = new int[extArea];
            this.bridgeSupportCapPresent = new boolean[extArea];
            this.bridgeSupportCapBottomY = new int[extArea];
            this.bridgeSupportCapTopY = new int[extArea];
            this.candidatePresent = new boolean[extArea];
            this.candidateDeckY = new int[extArea];
            this.candidateMode = new byte[extArea];
            this.candidateStyle = new byte[extArea];
            this.candidateMarking = new boolean[extArea];
            this.candidateTunnelCarve = new boolean[extArea];
            this.bridgeCandidatePresent = new boolean[extArea];
            this.bridgeCandidateDeckY = new int[extArea];
            this.bridgeCandidateStyle = new byte[extArea];
            this.bridgeCandidateMarking = new boolean[extArea];
            this.placed = new int[extArea];
         }
      }

      private void clearRoadExtState(int extArea) {
         Arrays.fill(this.resolvedClass, 0, extArea, (byte)0);
         Arrays.fill(this.resolvedMode, 0, extArea, (byte)0);
         Arrays.fill(this.resolvedStyle, 0, extArea, (byte)0);
         Arrays.fill(this.resolvedMarking, 0, extArea, false);
         Arrays.fill(this.resolvedDeckY, 0, extArea, 0);
         Arrays.fill(this.resolvedTunnelCarve, 0, extArea, false);
         Arrays.fill(this.blockedByHigherClass, 0, extArea, false);
         Arrays.fill(this.bridgeOverlayPresent, 0, extArea, false);
         Arrays.fill(this.bridgeOverlayDeckY, 0, extArea, 0);
         Arrays.fill(this.bridgeOverlayClass, 0, extArea, (byte)0);
         Arrays.fill(this.bridgeOverlayStyle, 0, extArea, (byte)0);
         Arrays.fill(this.bridgeOverlayMarking, 0, extArea, false);
         Arrays.fill(this.bridgeSupportShaftPresent, 0, extArea, false);
         Arrays.fill(this.bridgeSupportShaftBottomY, 0, extArea, 0);
         Arrays.fill(this.bridgeSupportShaftTopY, 0, extArea, 0);
         Arrays.fill(this.bridgeSupportCapPresent, 0, extArea, false);
         Arrays.fill(this.bridgeSupportCapBottomY, 0, extArea, 0);
         Arrays.fill(this.bridgeSupportCapTopY, 0, extArea, 0);
      }

      private void clearRoadCandidateState(int extArea) {
         Arrays.fill(this.candidatePresent, 0, extArea, false);
         Arrays.fill(this.candidateDeckY, 0, extArea, 0);
         Arrays.fill(this.candidateMode, 0, extArea, (byte)0);
         Arrays.fill(this.candidateStyle, 0, extArea, (byte)0);
         Arrays.fill(this.candidateMarking, 0, extArea, false);
         Arrays.fill(this.candidateTunnelCarve, 0, extArea, false);
         Arrays.fill(this.bridgeCandidatePresent, 0, extArea, false);
         Arrays.fill(this.bridgeCandidateDeckY, 0, extArea, 0);
         Arrays.fill(this.bridgeCandidateStyle, 0, extArea, (byte)0);
         Arrays.fill(this.bridgeCandidateMarking, 0, extArea, false);
      }
   }

   public record OsmRoadQueryResult(List<RoadFeature> features, boolean hadCacheMisses) {
      public OsmRoadQueryResult(List<RoadFeature> features, boolean hadCacheMisses) {
         features = features == null ? List.of() : List.copyOf(features);
         this.features = features;
         this.hadCacheMisses = hadCacheMisses;
      }
   }

   public record OsmRoadAreaQueryResult(List<RoadAreaFeature> features, boolean hadCacheMisses) {
      public OsmRoadAreaQueryResult(List<RoadAreaFeature> features, boolean hadCacheMisses) {
         features = features == null ? List.of() : List.copyOf(features);
         this.features = features;
         this.hadCacheMisses = hadCacheMisses;
      }
   }

   public record OsmStreetLightQueryResult(List<OsmStreetLightFeature> features, boolean hadCacheMisses) {
      public OsmStreetLightQueryResult(List<OsmStreetLightFeature> features, boolean hadCacheMisses) {
         features = features == null ? List.of() : List.copyOf(features);
         this.features = features;
         this.hadCacheMisses = hadCacheMisses;
      }
   }

   public record OsmBuildingQueryResult(List<OsmBuildingFeature> features, boolean hadCacheMisses) {
      public OsmBuildingQueryResult(List<OsmBuildingFeature> features, boolean hadCacheMisses) {
         features = features == null ? List.of() : List.copyOf(features);
         this.features = features;
         this.hadCacheMisses = hadCacheMisses;
      }
   }

   @FunctionalInterface
   private interface BuildingBiomeResolver {
      Holder<Biome> sample(OsmBuildingFeature feature, int baseY);
   }

   private record TerrainWarmupTicket(
      int heightGridMisses, int coverMisses, int visualCoverMisses, boolean waterFallback, boolean usedHeightFallback
   ) {
   }

   private record TerrainShellColumnFillResult(long surfaceCoverResolveNs, int coverMisses, int visualCoverMisses) {
   }

   private record TerrainShellBuildResult(
      ChunkPos pos,
      int minY,
      int maxY,
      int[] terrainSurfaces,
      int[] waterSurfaces,
      boolean[] waterFlags,
      int[] coverClasses,
      int[] visualCoverClasses,
      int[] surfaceCoverClasses,
      boolean[] oceanFlags,
      Holder<Biome>[] biomeCache,
      EarthChunkGenerator.TerrainWarmupTicket warmupTicket,
      long generationStamp
   ) {
      private static EarthChunkGenerator.TerrainShellBuildResult capture(
         ChunkPos pos,
         int minY,
         int maxY,
         int[] terrainSurfaces,
         int[] waterSurfaces,
         boolean[] waterFlags,
         int[] coverClasses,
         int[] visualCoverClasses,
         int[] surfaceCoverClasses,
         boolean[] oceanFlags,
         Holder<Biome>[] biomeCache,
         EarthChunkGenerator.TerrainWarmupTicket warmupTicket,
         long generationStamp
      ) {
         return new EarthChunkGenerator.TerrainShellBuildResult(
            pos,
            minY,
            maxY,
            terrainSurfaces.clone(),
            waterSurfaces.clone(),
            waterFlags.clone(),
            coverClasses.clone(),
            visualCoverClasses.clone(),
            surfaceCoverClasses.clone(),
            oceanFlags.clone(),
            Arrays.copyOf(biomeCache, biomeCache.length),
            warmupTicket,
            generationStamp
         );
      }

      private long chunkKey() {
         return MinecraftVersionCompat.packChunkPos(
            MinecraftVersionCompat.chunkX(this.pos), MinecraftVersionCompat.chunkZ(this.pos)
         );
      }
   }

   private record PreparedTerrainRefinement(
      EarthChunkGenerator.TerrainShellBuildResult shell,
      EarthChunkGenerator.ChunkGenerationContext context,
      EarthChunkGenerator.PreparedChunkDetail delayedDetail,
      int[] terrainSurfaces,
      int[] terrainShellBedrockSkinTopYs,
      int[] terrainShellBedrockCurtainBottomYs,
      int[] waterSurfaces,
      boolean[] waterFlags,
      int[] coverClasses,
      int[] visualCoverClasses,
      int[] surfaceCoverClasses,
      boolean[] oceanFlags,
      int[] slopeDiffs,
      int[] convexities
   ) {
      private long generationStamp() {
         return this.shell.generationStamp();
      }
   }

   private record ChunkGenerationContext(
      ChunkPos pos,
      int minY,
      int maxY,
      int[] terrainSurfaces,
      int[] waterSurfaces,
      boolean[] waterFlags,
      int[] coverClasses,
      Holder<Biome>[] biomeCache,
      EarthChunkGenerator.PreparedChunkBuildings preparedBuildings,
      Holder<Biome> fallbackBiome,
      long generationStamp
   ) {
      private static EarthChunkGenerator.ChunkGenerationContext capture(
         ChunkPos pos,
         int minY,
         int maxY,
         int[] terrainSurfaces,
         int[] waterSurfaces,
         boolean[] waterFlags,
         int[] coverClasses,
         Holder<Biome>[] biomeCache,
         EarthChunkGenerator.PreparedChunkBuildings preparedBuildings,
         long generationStamp
      ) {
         Holder<Biome>[] biomeCopy = Arrays.copyOf(biomeCache, biomeCache.length);
         Holder<Biome> fallbackBiome = null;

         for (Holder<Biome> biome : biomeCopy) {
            if (biome != null) {
               fallbackBiome = biome;
               break;
            }
         }

         if (fallbackBiome == null) {
            throw new IllegalStateException("Chunk generation context captured without a biome sample");
         } else {
            return new EarthChunkGenerator.ChunkGenerationContext(
               pos,
               minY,
               maxY,
               terrainSurfaces.clone(),
               waterSurfaces.clone(),
               waterFlags.clone(),
               coverClasses.clone(),
               biomeCopy,
               preparedBuildings,
               fallbackBiome,
               generationStamp
            );
         }
      }

      private long chunkKey() {
         return MinecraftVersionCompat.packChunkPos(
            MinecraftVersionCompat.chunkX(this.pos), MinecraftVersionCompat.chunkZ(this.pos)
         );
      }

      private Holder<Biome> sampleBiome(int worldX, int worldZ, int sampleY) {
         int localX = Mth.clamp(worldX - this.pos.getMinBlockX(), 0, CHUNK_MASK);
         int localZ = Mth.clamp(worldZ - this.pos.getMinBlockZ(), 0, CHUNK_MASK);
         Holder<Biome> biome = this.biomeCache[chunkIndex(localX, localZ)];
         return biome != null ? biome : this.fallbackBiome;
      }

      private int[] copyTerrainSurfaces() {
         return this.terrainSurfaces.clone();
      }

      private int[] copyWaterSurfaces() {
         return this.waterSurfaces.clone();
      }

      private boolean[] copyWaterFlags() {
         return this.waterFlags.clone();
      }
   }

   private record ChunkDecorationContext(
      ChunkPos pos,
      int[] terrainSurfaces,
      boolean[] waterFlags,
      int[] coverClasses,
      Holder<Biome>[] biomeCache,
      Holder<Biome> fallbackBiome,
      List<UndergroundStructureExclusion.Box> undergroundStructureExclusions
   ) {
      private static EarthChunkGenerator.ChunkDecorationContext capture(
         ChunkPos pos, int[] terrainSurfaces, boolean[] waterFlags, int[] coverClasses, Holder<Biome>[] biomeCache
      ) {
         Holder<Biome> fallbackBiome = null;

         for (Holder<Biome> biome : biomeCache) {
            if (biome != null) {
               fallbackBiome = biome;
               break;
            }
         }

         if (fallbackBiome == null) {
            throw new IllegalStateException("Chunk decoration context captured without a biome sample");
         } else {
            return new EarthChunkGenerator.ChunkDecorationContext(
               pos, terrainSurfaces, waterFlags, coverClasses, biomeCache, fallbackBiome, List.of()
            );
         }
      }

      private EarthChunkGenerator.ChunkDecorationContext withUndergroundStructureExclusions(
         List<UndergroundStructureExclusion.Box> exclusions
      ) {
         return new EarthChunkGenerator.ChunkDecorationContext(
            this.pos,
            this.terrainSurfaces,
            this.waterFlags,
            this.coverClasses,
            this.biomeCache,
            this.fallbackBiome,
            List.copyOf(exclusions)
         );
      }

      private int terrainSurface(int localX, int localZ) {
         return this.terrainSurfaces[chunkIndex(localX, localZ)];
      }

      private int coverClass(int localX, int localZ) {
         return this.coverClasses[chunkIndex(localX, localZ)];
      }

      private Holder<Biome> biome(int localX, int localZ) {
         Holder<Biome> biome = this.biomeCache[chunkIndex(localX, localZ)];
         return biome != null ? biome : this.fallbackBiome;
      }

      private boolean canResolveNearWaterWithinChunk(int localX, int localZ, int radius) {
         return localX - radius >= 0 && localX + radius <= CHUNK_MASK && localZ - radius >= 0 && localZ + radius <= CHUNK_MASK;
      }

      private boolean isNearWaterWithinChunk(int localX, int localZ, int radius) {
         for (int z = localZ - radius; z <= localZ + radius; z++) {
            for (int x = localX - radius; x <= localX + radius; x++) {
               if (this.waterFlags[chunkIndex(x, z)]) {
                  return true;
               }
            }
         }

         return false;
      }
   }

   private record PreparedChunkDetail(
      EarthChunkGenerator.ChunkGenerationContext context,
      EarthChunkGenerator.PreparedChunkBuildings preparedBuildings,
      boolean placeBuildings,
      EarthChunkGenerator.OsmRoadQueryResult roadQuery,
      List<EarthChunkGenerator.PreparedTreePlacement> treePlacements
   ) {
   }

   private record PreparedTreePlacement(int worldX, int worldZ, int expectedSurface, Holder<Biome> biome, long seed) {
   }

   @FunctionalInterface
   interface ClimateSampler {
      String sample(int blockX, int blockZ, double worldScale);
   }

   static final class HeightGridBuildResult {
      private final int[] heightGrid;
      private final int cacheHits;
      private final int cacheMisses;

      HeightGridBuildResult(int[] heightGrid, int cacheHits, int cacheMisses) {
         this.heightGrid = heightGrid;
         this.cacheHits = cacheHits;
         this.cacheMisses = cacheMisses;
      }

      int[] heightGrid() {
         return this.heightGrid;
      }

      int cacheHits() {
         return this.cacheHits;
      }

      int cacheMisses() {
         return this.cacheMisses;
      }
   }

   private record TerrainShellHeightGridResult(int[] heightGrid, int cacheHits, int missingCount, boolean usedFallback) {
      private static EarthChunkGenerator.TerrainShellHeightGridResult exact(EarthChunkGenerator.HeightGridBuildResult result) {
         return new EarthChunkGenerator.TerrainShellHeightGridResult(result.heightGrid(), result.cacheHits(), result.cacheMisses(), false);
      }

      private int cacheMisses() {
         return this.missingCount;
      }
   }

   static final class HeightGridCache {
      private final int maxEntries;
      private final LinkedHashMap<Long, EarthChunkGenerator.HeightGridCacheEntry> entries;

      HeightGridCache(int maxEntries) {
         this.maxEntries = maxEntries;
         this.entries = maxEntries <= 0 ? null : new LinkedHashMap<>(maxEntries, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Entry<Long, EarthChunkGenerator.HeightGridCacheEntry> eldest) {
               return size() > HeightGridCache.this.maxEntries;
            }
         };
      }

      synchronized int copyOverlaps(ChunkPos pos, int step, int gridSize, int[] target, boolean allowApproximate) {
         if (this.entries == null || !isReusableLayout(step, gridSize)) {
            return 0;
         }

         int copied = 0;
         for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
               if (dx == 0 && dz == 0) {
                  continue;
               }

               EarthChunkGenerator.HeightGridCacheEntry entry = this.entries.get(
                  MinecraftVersionCompat.packChunkPos(
                     MinecraftVersionCompat.chunkX(pos) + dx, MinecraftVersionCompat.chunkZ(pos) + dz
                  )
               );
               if (entry != null && (allowApproximate || !entry.approximate())) {
                  copied += entry.copyOverlapTo(pos, step, gridSize, target);
               }
            }
         }

         return copied;
      }

      synchronized void put(ChunkPos pos, int step, int gridSize, int[] heightGrid, boolean approximate) {
         if (this.entries != null && isReusableLayout(step, gridSize)) {
            this.entries.put(
               MinecraftVersionCompat.packChunkPos(MinecraftVersionCompat.chunkX(pos), MinecraftVersionCompat.chunkZ(pos)),
               new EarthChunkGenerator.HeightGridCacheEntry(pos, step, gridSize, heightGrid.clone(), approximate)
            );
         }
      }

      private static boolean isReusableLayout(int step, int gridSize) {
         return step == 4 && gridSize == 24;
      }
   }

   static final class HeightGridCacheEntry {
      private final ChunkPos pos;
      private final int step;
      private final int gridSize;
      private final int[] heightGrid;
      private final boolean approximate;

      HeightGridCacheEntry(ChunkPos pos, int step, int gridSize, int[] heightGrid, boolean approximate) {
         this.pos = pos;
         this.step = step;
         this.gridSize = gridSize;
         this.heightGrid = heightGrid;
         this.approximate = approximate;
      }

      boolean approximate() {
         return this.approximate;
      }

      int copyOverlapTo(ChunkPos targetPos, int step, int gridSize, int[] target) {
         if (this.step != step || this.gridSize != gridSize) {
            return 0;
         }

         int sourceMinX = this.pos.getMinBlockX() - step;
         int sourceMinZ = this.pos.getMinBlockZ() - step;
         int targetMinX = targetPos.getMinBlockX() - step;
         int targetMinZ = targetPos.getMinBlockZ() - step;
         int copied = 0;

         for (int dz = 0; dz < gridSize; dz++) {
            int sourceZ = targetMinZ + dz - sourceMinZ;
            if (sourceZ < 0 || sourceZ >= gridSize) {
               continue;
            }

            int targetRow = dz * gridSize;
            int sourceRow = sourceZ * gridSize;
            for (int dx = 0; dx < gridSize; dx++) {
               int sourceX = targetMinX + dx - sourceMinX;
               if (sourceX < 0 || sourceX >= gridSize) {
                  continue;
               }

               int index = targetRow + dx;
               if (target[index] == Integer.MIN_VALUE) {
                  target[index] = this.heightGrid[sourceRow + sourceX];
                  copied++;
               }
            }
         }

         return copied;
      }
   }

   static final class ChunkBiomeClimateCache {
      private static final int CELL_SIZE = 4;
      private static final int GRID_SIZE = CHUNK_SIDE / CELL_SIZE;
      private final int chunkMinX;
      private final int chunkMinZ;
      private final double worldScale;
      private final String[] codes = new String[GRID_SIZE * GRID_SIZE];
      private final boolean[] resolved = new boolean[this.codes.length];
      private int hits;
      private int misses;

      ChunkBiomeClimateCache(ChunkPos pos, double worldScale) {
         this.chunkMinX = pos.getMinBlockX();
         this.chunkMinZ = pos.getMinBlockZ();
         this.worldScale = worldScale;
      }

      String resolve(int worldX, int worldZ, EarthChunkGenerator.ClimateSampler sampler) {
         int localX = Mth.clamp(worldX - this.chunkMinX, 0, CHUNK_MASK);
         int localZ = Mth.clamp(worldZ - this.chunkMinZ, 0, CHUNK_MASK);
         int index = cellIndex(localX, localZ);
         if (this.resolved[index]) {
            this.hits++;
            return this.codes[index];
         }

         int cellX = localX >> 2;
         int cellZ = localZ >> 2;
         int sampleX = this.chunkMinX + (cellX << 2) + 2;
         int sampleZ = this.chunkMinZ + (cellZ << 2) + 2;
         this.codes[index] = sampler.sample(sampleX, sampleZ, this.worldScale);
         this.resolved[index] = true;
         this.misses++;
         return this.codes[index];
      }

      static int cellIndex(int localX, int localZ) {
         return (localZ >> 2) * GRID_SIZE + (localX >> 2);
      }

      int hitCount() {
         return this.hits;
      }

      int missCount() {
         return this.misses;
      }
   }

   static final class ChunkSectionWriter {
      private final LevelChunkSection[] sections;
      private final int chunkMinY;
      private final boolean[] dirtySections;
      private final boolean[] acquiredSections;
      private final PalettedContainer<BlockState>[] stateContainers;
      private int dirtySectionCount;
      private boolean closed;

      @SuppressWarnings("unchecked")
      ChunkSectionWriter(LevelChunkSection[] sections, int chunkMinY) {
         this.sections = sections;
         this.chunkMinY = chunkMinY;
         this.dirtySections = new boolean[sections.length];
         this.acquiredSections = new boolean[sections.length];
         this.stateContainers = (PalettedContainer<BlockState>[])new PalettedContainer[sections.length];
      }

      void fillSectionConstant(int sectionIndex, BlockState state, EarthChunkGenerator.SolidSectionFillProfiler profiler) {
         PalettedContainer<BlockState> states = this.acquireStates(sectionIndex, profiler);
         long sectionWriteStartNs = beginFullChunkProfiling();

         for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
               for (int x = 0; x < 16; x++) {
                  states.getAndSetUnchecked(x, y, z, state);
               }
            }
         }

         profiler.sectionWriteNs += elapsedFullChunkProfilingSince(sectionWriteStartNs);
         this.markDirty(sectionIndex);
      }

      void fillStoneColumnSpan(int localX, int localZ, int startY, int endY, int deepslateStart, BlockState stone, BlockState deepslate) {
         if (endY < startY) {
            return;
         }

         int currentY = startY;
         while (currentY <= endY) {
            int sectionIndex = this.sectionIndexForY(currentY);
            if (sectionIndex < 0 || sectionIndex >= this.sections.length) {
               break;
            }

            int sectionBottomY = this.chunkMinY + (sectionIndex << 4);
            int localStartY = Math.max(0, currentY - sectionBottomY);
            int localEndY = Math.min(15, endY - sectionBottomY);
            PalettedContainer<BlockState> states = this.acquireStates(sectionIndex, null);
            for (int localY = localStartY; localY <= localEndY; localY++) {
               int worldY = sectionBottomY + localY;
               states.getAndSetUnchecked(localX, localY, localZ, worldY < deepslateStart ? deepslate : stone);
            }

            this.markDirty(sectionIndex);
            currentY = sectionBottomY + 16;
         }
      }

      void fillColumnConstant(int localX, int localZ, int startY, int endY, BlockState state) {
         if (endY < startY) {
            return;
         }

         int currentY = startY;
         while (currentY <= endY) {
            int sectionIndex = this.sectionIndexForY(currentY);
            if (sectionIndex < 0 || sectionIndex >= this.sections.length) {
               break;
            }

            int sectionBottomY = this.chunkMinY + (sectionIndex << 4);
            int localStartY = Math.max(0, currentY - sectionBottomY);
            int localEndY = Math.min(15, endY - sectionBottomY);
            PalettedContainer<BlockState> states = this.acquireStates(sectionIndex, null);
            for (int localY = localStartY; localY <= localEndY; localY++) {
               states.getAndSetUnchecked(localX, localY, localZ, state);
            }

            this.markDirty(sectionIndex);
            currentY = sectionBottomY + 16;
         }
      }

      void fillSurfaceColumn(int localX, int localZ, int surfaceY, int bottomY, BlockState top, BlockState filler) {
         if (surfaceY < bottomY) {
            return;
         }

         int currentY = surfaceY;
         while (currentY >= bottomY) {
            int sectionIndex = this.sectionIndexForY(currentY);
            if (sectionIndex < 0 || sectionIndex >= this.sections.length) {
               break;
            }

            int sectionBottomY = this.chunkMinY + (sectionIndex << 4);
            int localTopY = Math.min(15, currentY - sectionBottomY);
            int localBottomY = Math.max(0, bottomY - sectionBottomY);
            PalettedContainer<BlockState> states = this.acquireStates(sectionIndex, null);
            for (int localY = localTopY; localY >= localBottomY; localY--) {
               states.getAndSetUnchecked(localX, localY, localZ, sectionBottomY + localY == surfaceY ? top : filler);
            }

            this.markDirty(sectionIndex);
            currentY = sectionBottomY - 1;
         }
      }

      void fillBadlandsBands(
         int localX, int localZ, int worldX, int worldZ, int surfaceY, int bottomY, BlockState top
      ) {
         if (surfaceY < bottomY) {
            return;
         }

         int currentY = surfaceY;
         while (currentY >= bottomY) {
            int sectionIndex = this.sectionIndexForY(currentY);
            if (sectionIndex < 0 || sectionIndex >= this.sections.length) {
               break;
            }

            int sectionBottomY = this.chunkMinY + (sectionIndex << 4);
            int localTopY = Math.min(15, currentY - sectionBottomY);
            int localBottomY = Math.max(0, bottomY - sectionBottomY);
            PalettedContainer<BlockState> states = this.acquireStates(sectionIndex, null);
            for (int localY = localTopY; localY >= localBottomY; localY--) {
               int worldY = sectionBottomY + localY;
               states.getAndSetUnchecked(localX, localY, localZ, worldY == surfaceY ? top : badlandsBand(worldX, worldZ, worldY));
            }

            this.markDirty(sectionIndex);
            currentY = sectionBottomY - 1;
         }
      }

      void setBlock(int localX, int localZ, int worldY, BlockState state) {
         int sectionIndex = this.sectionIndexForY(worldY);
         if (sectionIndex < 0 || sectionIndex >= this.sections.length) {
            return;
         }

         int sectionBottomY = this.chunkMinY + (sectionIndex << 4);
         this.acquireStates(sectionIndex, null).getAndSetUnchecked(localX, worldY - sectionBottomY, localZ, state);
         this.markDirty(sectionIndex);
      }

      FlushResult finish() {
         if (this.closed) {
            return new FlushResult(0L, this.dirtySectionCount);
         }

         this.releaseSections();
         long recalcStartNs = beginFullChunkProfiling();
         for (int i = 0; i < this.sections.length; i++) {
            if (this.dirtySections[i]) {
               Objects.requireNonNull(this.sections[i], "section").recalcBlockCounts();
            }
         }

         this.closed = true;
         return new FlushResult(elapsedFullChunkProfilingSince(recalcStartNs), this.dirtySectionCount);
      }

      void close() {
         if (!this.closed) {
            this.releaseSections();
            this.closed = true;
         }
      }

      private void releaseSections() {
         for (int i = 0; i < this.sections.length; i++) {
            if (this.acquiredSections[i]) {
               Objects.requireNonNull(this.sections[i], "section").release();
               this.acquiredSections[i] = false;
            }
         }
      }

      private int sectionIndexForY(int worldY) {
         return (worldY - this.chunkMinY) >> 4;
      }

      private PalettedContainer<BlockState> acquireStates(int sectionIndex, EarthChunkGenerator.SolidSectionFillProfiler profiler) {
         if (sectionIndex < 0 || sectionIndex >= this.sections.length) {
            throw new IndexOutOfBoundsException("sectionIndex=" + sectionIndex);
         }

         if (!this.acquiredSections[sectionIndex]) {
            long sectionAccessStartNs = beginFullChunkProfiling();
            LevelChunkSection section = Objects.requireNonNull(this.sections[sectionIndex], "section");
            section.acquire();
            this.stateContainers[sectionIndex] = section.getStates();
            this.acquiredSections[sectionIndex] = true;
            if (profiler != null) {
               profiler.sectionAccessNs += elapsedFullChunkProfilingSince(sectionAccessStartNs);
            }
         }

         return Objects.requireNonNull(this.stateContainers[sectionIndex], "states");
      }

      private void markDirty(int sectionIndex) {
         if (!this.dirtySections[sectionIndex]) {
            this.dirtySections[sectionIndex] = true;
            this.dirtySectionCount++;
         }
      }

      static final class FlushResult {
         private final long recalcNs;
         private final int dirtySectionCount;

         FlushResult(long recalcNs, int dirtySectionCount) {
            this.recalcNs = recalcNs;
            this.dirtySectionCount = dirtySectionCount;
         }

         long recalcNs() {
            return this.recalcNs;
         }

         int dirtySectionCount() {
            return this.dirtySectionCount;
         }
      }
   }

   private final class MountainSamplingCache {
      private final Long2ByteOpenHashMap surfaceCoverClasses = new Long2ByteOpenHashMap();
      private double previewResolutionMeters = Double.NaN;

      private MountainSamplingCache() {
         this.surfaceCoverClasses.defaultReturnValue((byte)-1);
      }

      private void prepare(double previewResolutionMeters) {
         if (Double.doubleToLongBits(this.previewResolutionMeters) != Double.doubleToLongBits(previewResolutionMeters)) {
            this.previewResolutionMeters = previewResolutionMeters;
            this.surfaceCoverClasses.clear();
         }
      }

      private int surfaceCoverClass(int worldX, int worldZ) {
         long key = packColumn(worldX, worldZ);
         byte cached = this.surfaceCoverClasses.get(key);
         if (cached != (byte)-1) {
            return Byte.toUnsignedInt(cached);
         }

         int rawCoverClass = EarthChunkGenerator.this.sampleCoverClass(worldX, worldZ, this.previewResolutionMeters);
         int visualCoverClass = EarthChunkGenerator.this.sampleVisualCoverClass(
            worldX, worldZ, rawCoverClass, this.previewResolutionMeters
         );
         int surfaceCoverClass = MountainSurfaceRules.resolveSurfaceCoverClass(rawCoverClass, visualCoverClass);
         if (MOUNTAIN_SNOW_SOURCE_CACHE_ENTRIES > 0) {
            if (this.surfaceCoverClasses.size() >= MOUNTAIN_SNOW_SOURCE_CACHE_ENTRIES) {
               this.surfaceCoverClasses.clear();
            }
            this.surfaceCoverClasses.put(key, (byte)surfaceCoverClass);
         }
         return surfaceCoverClass;
      }

   }

   private static final class TerrainRefinementManager {
      // shortcut: jobs live only in memory, so a shell chunk that unloads and then misses its refinement because the
      // server stops keeps the shell; persist pending chunk keys with the level if players report leftover shells.
      private final Map<Long, EarthChunkGenerator.TerrainRefinementJob> jobs = new ConcurrentHashMap<>();
      private final ConcurrentLinkedQueue<Long> readyQueue = new ConcurrentLinkedQueue<>();
      private final Map<Long, Integer> oceanRetryAttempts = new ConcurrentHashMap<>();

      private void schedule(EarthChunkGenerator generator, EarthChunkGenerator.TerrainShellBuildResult shell) {
         long chunkKey = shell.chunkKey();
         EarthChunkGenerator.TerrainRefinementJob job = new EarthChunkGenerator.TerrainRefinementJob(EarthChunkGenerator.TerrainRefinementJobState.QUEUED);
         EarthChunkGenerator.TerrainRefinementJob previous = this.jobs.put(chunkKey, job);
         if (previous != null) {
            previous.state = EarthChunkGenerator.TerrainRefinementJobState.STALE;
            EarthChunkGenerator.TerrainStreamingPerf.recordRefinementStaleDrop();
         }

         EarthChunkGenerator.TerrainStreamingPerf.recordRefinementQueued();
         EarthChunkGenerator.TerrainStreamingPerf.recordShellBuild(shell.warmupTicket());
         TellusWorldgenSources.submitTerrainDetailTask(() -> {
            AtomicBoolean shouldFetch = new AtomicBoolean();
            AtomicBoolean staleBeforeFetch = new AtomicBoolean();
            this.jobs.compute(chunkKey, (key, current) -> {
               if (current != job) {
                  job.state = EarthChunkGenerator.TerrainRefinementJobState.STALE;
                  staleBeforeFetch.set(true);
                  return current;
               }

               job.state = EarthChunkGenerator.TerrainRefinementJobState.FETCHING;
               shouldFetch.set(true);
               return current;
            });
            if (!shouldFetch.get()) {
               if (staleBeforeFetch.get()) {
                  EarthChunkGenerator.TerrainStreamingPerf.recordRefinementStaleDrop();
               }
               return;
            }

            try {
               job.refinement = generator.buildPreparedTerrainRefinement(shell);
            } catch (RuntimeException error) {
               if (error instanceof OceanCoverageUnavailableException
                  || error.getCause() instanceof OceanCoverageUnavailableException
                  || error instanceof ElevationUnavailableException) {
                  int attempt = this.oceanRetryAttempts.merge(chunkKey, 1, Integer::sum);
                  long delaySeconds = oceanCoverageRetryDelaySeconds(attempt);
                  job.state = EarthChunkGenerator.TerrainRefinementJobState.QUEUED;
                  CompletableFuture.runAsync(
                     () -> {
                        if (this.jobs.get(chunkKey) == job) {
                           this.schedule(generator, shell);
                        }
                     },
                     CompletableFuture.delayedExecutor(delaySeconds, TimeUnit.SECONDS)
                  );
                  return;
               }
               job.state = EarthChunkGenerator.TerrainRefinementJobState.FAILED;
               Tellus.LOGGER.debug("Failed to build terrain refinement for {}", shell.pos(), error);
               this.jobs.remove(chunkKey, job);
               return;
            }
            this.oceanRetryAttempts.remove(chunkKey);

            AtomicBoolean staleAfterBuild = new AtomicBoolean();
            this.jobs.compute(chunkKey, (key, current) -> {
               if (current != job) {
                  job.state = EarthChunkGenerator.TerrainRefinementJobState.STALE;
                  staleAfterBuild.set(true);
                  return current;
               }

               job.state = EarthChunkGenerator.TerrainRefinementJobState.READY;
               this.readyQueue.offer(chunkKey);
               return current;
            });
            if (staleAfterBuild.get()) {
               EarthChunkGenerator.TerrainStreamingPerf.recordRefinementStaleDrop();
            }
         });
      }

      private boolean hasPending(ChunkPos pos) {
         EarthChunkGenerator.TerrainRefinementJob job = this.jobs.get(
            MinecraftVersionCompat.packChunkPos(MinecraftVersionCompat.chunkX(pos), MinecraftVersionCompat.chunkZ(pos))
         );
         return job != null && job.state != EarthChunkGenerator.TerrainRefinementJobState.APPLIED && job.state != EarthChunkGenerator.TerrainRefinementJobState.FAILED;
      }

      private EarthChunkGenerator.PreparedTerrainRefinement claimReady(ChunkPos pos) {
         long chunkKey = MinecraftVersionCompat.packChunkPos(MinecraftVersionCompat.chunkX(pos), MinecraftVersionCompat.chunkZ(pos));
         EarthChunkGenerator.TerrainRefinementJob job = this.jobs.get(chunkKey);
         if (job == null || job.state != EarthChunkGenerator.TerrainRefinementJobState.READY) {
            return null;
         } else if (!this.jobs.remove(chunkKey, job)) {
            return null;
         } else {
            job.state = EarthChunkGenerator.TerrainRefinementJobState.APPLIED;
            return job.refinement;
         }
      }

      private void applyReady(ServerLevel level, EarthChunkGenerator generator, int budget) {
         if (budget <= 0) {
            return;
         }

         int applied = 0;
         int scanned = 0;
         int maxScans = Math.max(budget * 4, budget);

         while (applied < budget && scanned < maxScans) {
            Long chunkKey = this.readyQueue.poll();
            if (chunkKey == null) {
               break;
            }

            scanned++;
            EarthChunkGenerator.TerrainRefinementJob job = this.jobs.get(chunkKey);
            if (job == null || job.state != EarthChunkGenerator.TerrainRefinementJobState.READY) {
               continue;
            }

            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
            if (chunk == null) {
               this.readyQueue.offer(chunkKey);
               continue;
            }

            EarthChunkGenerator.PreparedTerrainRefinement refinement = this.claimReady(chunk.getPos());
            if (refinement == null) {
               continue;
            }

            try {
               generator.applyPreparedTerrainRefinement(level, chunk, refinement);
               applied++;
               EarthChunkGenerator.TerrainStreamingPerf.recordRefinementApplied();
            } catch (RuntimeException error) {
               Tellus.LOGGER.debug("Failed to apply terrain refinement for {}", chunk.getPos(), error);
            }
         }
      }

      private static long oceanCoverageRetryDelaySeconds(int attempt) {
         if (attempt <= 0) {
            return 0L;
         }
         return attempt <= 5 ? 1L << (attempt - 1) : 60L;
      }
   }

   private static final class ChunkDetailManager {
      private final Map<Long, EarthChunkGenerator.ChunkDetailJob> jobs = new ConcurrentHashMap<>();
      private final ConcurrentLinkedQueue<Long> readyQueue = new ConcurrentLinkedQueue<>();
      private final Map<UUID, EarthChunkGenerator.ChunkDetailManager.PlayerPrefetchState> playerPrefetchStates = new ConcurrentHashMap<>();

      private void schedule(EarthChunkGenerator generator, EarthChunkGenerator.ChunkGenerationContext context) {
         long chunkKey = context.chunkKey();
         EarthChunkGenerator.ChunkDetailJob job = new EarthChunkGenerator.ChunkDetailJob(EarthChunkGenerator.ChunkDetailJobState.QUEUED);
         EarthChunkGenerator.ChunkDetailJob previous = this.jobs.put(chunkKey, job);
         if (previous != null) {
            previous.state = EarthChunkGenerator.ChunkDetailJobState.STALE;
            EarthChunkGenerator.ChunkDetailPerf.recordStaleDrop();
         }

         EarthChunkGenerator.ChunkDetailPerf.recordQueueDepth(this.jobs.size());
         TellusWorldgenSources.submitTerrainDetailTask(() -> {
            AtomicBoolean shouldFetch = new AtomicBoolean();
            AtomicBoolean staleBeforeFetch = new AtomicBoolean();
            this.jobs.compute(chunkKey, (key, current) -> {
               if (current != job) {
                  job.state = EarthChunkGenerator.ChunkDetailJobState.STALE;
                  staleBeforeFetch.set(true);
                  return current;
               }

               job.state = EarthChunkGenerator.ChunkDetailJobState.FETCHING;
               shouldFetch.set(true);
               return current;
            });
            if (!shouldFetch.get()) {
               if (staleBeforeFetch.get()) {
                  EarthChunkGenerator.ChunkDetailPerf.recordStaleDrop();
               }
               return;
            }

            try {
               job.detail = generator.prepareDeferredChunkDetail(context);
            } catch (RuntimeException error) {
               EarthChunkGenerator.ChunkDetailPerf.recordFailure();
               job.state = EarthChunkGenerator.ChunkDetailJobState.FAILED;
               Tellus.LOGGER.debug("Failed to prepare deferred chunk detail for {}", context.pos(), error);
               this.jobs.remove(chunkKey, job);
               return;
            }

            AtomicBoolean staleAfterBuild = new AtomicBoolean();
            AtomicBoolean ready = new AtomicBoolean();
            this.jobs.compute(chunkKey, (key, current) -> {
               if (current != job) {
                  job.state = EarthChunkGenerator.ChunkDetailJobState.STALE;
                  staleAfterBuild.set(true);
                  return current;
               }

               job.state = EarthChunkGenerator.ChunkDetailJobState.READY;
               this.readyQueue.offer(chunkKey);
               ready.set(true);
               return current;
            });
            if (staleAfterBuild.get()) {
               EarthChunkGenerator.ChunkDetailPerf.recordStaleDrop();
            } else if (ready.get()) {
               EarthChunkGenerator.ChunkDetailPerf.recordDetailJob(EarthChunkGenerator.ChunkDetailPerf.elapsedSince(job.createdNs));
            }
         });
      }

      private EarthChunkGenerator.PreparedChunkDetail claimReady(ChunkPos pos) {
         long chunkKey = MinecraftVersionCompat.packChunkPos(MinecraftVersionCompat.chunkX(pos), MinecraftVersionCompat.chunkZ(pos));
         EarthChunkGenerator.ChunkDetailJob job = this.jobs.get(chunkKey);
         if (job == null || job.state != EarthChunkGenerator.ChunkDetailJobState.READY) {
            return null;
         } else if (!this.jobs.remove(chunkKey, job)) {
            return null;
         } else {
            job.state = EarthChunkGenerator.ChunkDetailJobState.APPLIED;
            return job.detail;
         }
      }

      private void applyReady(ServerLevel level, EarthChunkGenerator generator, int budget) {
         if (budget <= 0) {
            return;
         }

         int applied = 0;
         int scanned = 0;
         int maxScans = Math.max(budget * 4, budget);

         while (applied < budget && scanned < maxScans) {
            Long chunkKey = this.readyQueue.poll();
            if (chunkKey == null) {
               break;
            }

            scanned++;
            EarthChunkGenerator.ChunkDetailJob job = this.jobs.get(chunkKey);
            if (job == null || job.state != EarthChunkGenerator.ChunkDetailJobState.READY) {
               continue;
            }

            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
            if (chunk == null) {
               this.readyQueue.offer(chunkKey);
               continue;
            }

            EarthChunkGenerator.PreparedChunkDetail detail = this.claimReady(chunk.getPos());
            if (detail == null) {
               continue;
            }

            try {
               generator.applyPreparedChunkDetail(
                  level, chunk, detail, generator.abandonedCampPieces(level.registryAccess(), level.structureManager(), chunk.getPos())
               );
               applied++;
            } catch (RuntimeException error) {
               EarthChunkGenerator.ChunkDetailPerf.recordFailure();
               Tellus.LOGGER.debug("Failed to apply deferred chunk detail for {}", chunk.getPos(), error);
            }
         }
      }

      private void prefetchAroundPlayers(ServerLevel level, EarthChunkGenerator generator, int radius) {
         Set<UUID> activePlayers = new HashSet<>();
         long gameTime = level.getGameTime();

         for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player.level() != level) {
               continue;
            }

            UUID playerId = player.getUUID();
            activePlayers.add(playerId);
            EarthChunkGenerator.ChunkDetailManager.PlayerPrefetchState previousState = this.playerPrefetchStates.get(playerId);
            ChunkPos pos = player.chunkPosition();
            long chunkKey = MinecraftVersionCompat.packChunkPos(MinecraftVersionCompat.chunkX(pos), MinecraftVersionCompat.chunkZ(pos));
            Long previousChunkKey = previousState == null ? null : previousState.chunkKey();
            EarthChunkGenerator.ChunkDetailManager.PrefetchVector direction = this.resolvePrefetchDirection(player, pos, previousChunkKey);
            boolean changedChunk = previousState == null || previousState.chunkKey() != chunkKey;
            if (!changedChunk && !this.shouldRefreshForDirectionChange(player, pos, previousState, direction, gameTime)) {
               continue;
            }

            this.playerPrefetchStates.put(playerId, new EarthChunkGenerator.ChunkDetailManager.PlayerPrefetchState(chunkKey, direction, gameTime));

            boolean includeRoadsPrefetch = generator.shouldDeferRoadDetails();
            boolean includeDetailedWaterPrefetch = generator.shouldDeferDetailedWater();
            boolean includeBuildingsPrefetch = generator.shouldDeferBuildingDetails();
            double previewResolutionMeters = generator.settings.worldScale();
            boolean teleportBurst = changedChunk && this.shouldUseTeleportBurst(pos, previousChunkKey);
            List<ChunkPos> terrainTargets = this.buildMovementAwareTargets(
               pos,
               direction,
               teleportBurst ? Math.max(radius + MOVEMENT_PREFETCH_FORWARD_EXTRA, MOVEMENT_PREFETCH_TELEPORT_BURST_RADIUS) : radius + MOVEMENT_PREFETCH_FORWARD_EXTRA,
               teleportBurst ? Math.max(radius + MOVEMENT_PREFETCH_SIDE_EXTRA, Math.max(radius, MOVEMENT_PREFETCH_TELEPORT_BURST_RADIUS / 2)) : radius + MOVEMENT_PREFETCH_SIDE_EXTRA,
               teleportBurst ? radius : Math.max(0, radius - 1)
            );
            for (ChunkPos target : terrainTargets) {
               TellusWorldgenSources.prefetchTerrainForChunk(target, generator.settings, previewResolutionMeters, false);
            }

            List<ChunkPos> waterTargets = this.buildMovementAwareTargets(
               pos,
               direction,
               teleportBurst ? Math.max(radius + 1, MOVEMENT_PREFETCH_TELEPORT_BURST_RADIUS - 1) : radius + 1,
               teleportBurst ? Math.max(radius, MOVEMENT_PREFETCH_TELEPORT_BURST_RADIUS / 2) : radius,
               Math.max(0, radius - 1)
            );
            for (ChunkPos target : waterTargets) {
               TellusWorldgenSources.prefetchWaterForChunk(target, generator.settings, includeDetailedWaterPrefetch, previewResolutionMeters, false);
            }

            List<ChunkPos> osmTargets = this.buildMovementAwareTargets(pos, direction, radius + 1, radius, Math.max(0, radius - 1));
            for (ChunkPos target : osmTargets) {
               TellusWorldgenSources.prefetchOsmDetailsForChunk(
                  target, generator.settings, includeRoadsPrefetch, includeBuildingsPrefetch, previewResolutionMeters, false
               );
            }
         }

         this.playerPrefetchStates.keySet().removeIf(playerId -> !activePlayers.contains(playerId));
      }

      private boolean shouldUseTeleportBurst(ChunkPos current, Long previousChunkKey) {
         if (previousChunkKey == null) {
            return true;
         } else {
            int deltaX = Math.abs(MinecraftVersionCompat.chunkX(current) - ChunkPos.getX(previousChunkKey));
            int deltaZ = Math.abs(MinecraftVersionCompat.chunkZ(current) - ChunkPos.getZ(previousChunkKey));
            return Math.max(deltaX, deltaZ) >= MOVEMENT_PREFETCH_TELEPORT_THRESHOLD_CHUNKS;
         }
      }

      private EarthChunkGenerator.ChunkDetailManager.PrefetchVector resolvePrefetchDirection(ServerPlayer player, ChunkPos current, Long previousChunkKey) {
         if (previousChunkKey != null) {
            int deltaX = MinecraftVersionCompat.chunkX(current) - ChunkPos.getX(previousChunkKey);
            int deltaZ = MinecraftVersionCompat.chunkZ(current) - ChunkPos.getZ(previousChunkKey);
            EarthChunkGenerator.ChunkDetailManager.PrefetchVector fromChunkDelta = EarthChunkGenerator.ChunkDetailManager.PrefetchVector.normalized(deltaX, deltaZ);
            if (fromChunkDelta.hasDirection()) {
               return fromChunkDelta;
            }
         }

         Vec3 deltaMovement = player.getDeltaMovement();
         EarthChunkGenerator.ChunkDetailManager.PrefetchVector fromMotion = EarthChunkGenerator.ChunkDetailManager.PrefetchVector.normalized(
            deltaMovement.x, deltaMovement.z
         );
         if (fromMotion.hasDirection()) {
            return fromMotion;
         } else {
            Vec3 look = player.getLookAngle();
            return EarthChunkGenerator.ChunkDetailManager.PrefetchVector.normalized(look.x, look.z);
         }
      }

      private boolean shouldRefreshForDirectionChange(
         ServerPlayer player,
         ChunkPos current,
         EarthChunkGenerator.ChunkDetailManager.PlayerPrefetchState previousState,
         EarthChunkGenerator.ChunkDetailManager.PrefetchVector direction,
         long gameTime
      ) {
         if (previousState == null || !direction.hasDirection() || !previousState.direction().hasDirection()) {
            return false;
         } else if (gameTime - previousState.lastPrefetchGameTime() < MOVEMENT_PREFETCH_DIRECTION_CHANGE_COOLDOWN_TICKS) {
            return false;
         } else if (direction.dot(previousState.direction()) > MOVEMENT_PREFETCH_DIRECTION_CHANGE_MAX_DOT) {
            return false;
         } else {
            return this.hasDirectionalRefreshSignal(player, current, direction);
         }
      }

      private boolean hasDirectionalRefreshSignal(
         ServerPlayer player, ChunkPos current, EarthChunkGenerator.ChunkDetailManager.PrefetchVector direction
      ) {
         Vec3 velocity = player.getDeltaMovement();
         double speedSq = velocity.x * velocity.x + velocity.z * velocity.z;
         return speedSq >= MOVEMENT_PREFETCH_MIN_SPEED_SQ || this.isNearLeadingChunkEdge(player, current, direction);
      }

      private boolean isNearLeadingChunkEdge(
         ServerPlayer player, ChunkPos current, EarthChunkGenerator.ChunkDetailManager.PrefetchVector direction
      ) {
         double localX = Mth.clamp(player.getX() - current.getMinBlockX(), 0.0, CHUNK_SIDE);
         double localZ = Mth.clamp(player.getZ() - current.getMinBlockZ(), 0.0, CHUNK_SIDE);
         double edgeDistance = Double.POSITIVE_INFINITY;
         if (direction.x() > 0.25) {
            edgeDistance = Math.min(edgeDistance, CHUNK_SIDE - localX);
         } else if (direction.x() < -0.25) {
            edgeDistance = Math.min(edgeDistance, localX);
         }

         if (direction.z() > 0.25) {
            edgeDistance = Math.min(edgeDistance, CHUNK_SIDE - localZ);
         } else if (direction.z() < -0.25) {
            edgeDistance = Math.min(edgeDistance, localZ);
         }

         return edgeDistance <= MOVEMENT_PREFETCH_EDGE_TRIGGER_DISTANCE_BLOCKS;
      }

      private List<ChunkPos> buildMovementAwareTargets(
         ChunkPos center, EarthChunkGenerator.ChunkDetailManager.PrefetchVector direction, int forwardReach, int sideReach, int rearReach
      ) {
         int clampedForwardReach = Math.max(0, forwardReach);
         int clampedSideReach = Math.max(0, sideReach);
         int clampedRearReach = Math.max(0, rearReach);
         int span = Math.max(clampedForwardReach, Math.max(clampedSideReach, clampedRearReach));
         if (!direction.hasDirection()) {
            return this.buildSymmetricTargets(center, span);
         } else {
            double dirX = direction.x();
            double dirZ = direction.z();
            double perpX = -dirZ;
            double perpZ = dirX;
            List<EarthChunkGenerator.ChunkDetailManager.PrefetchTarget> ranked = new ArrayList<>();

            for (int dz = -span; dz <= span; dz++) {
               for (int dx = -span; dx <= span; dx++) {
                  double forward = dx * dirX + dz * dirZ;
                  double side = Math.abs(dx * perpX + dz * perpZ);
                  if (!(forward < -clampedRearReach - 0.25) && !(forward > clampedForwardReach + 0.25) && !(side > clampedSideReach + 0.25)) {
                     int chebyshev = Math.max(Math.abs(dx), Math.abs(dz));
                     int manhattan = Math.abs(dx) + Math.abs(dz);
                     double score = dx == 0 && dz == 0 ? Double.NEGATIVE_INFINITY : chebyshev * 8.0 + manhattan * 1.5 + side * 2.0 - forward * 3.0;
                     ranked.add(
                        new EarthChunkGenerator.ChunkDetailManager.PrefetchTarget(
                           new ChunkPos(MinecraftVersionCompat.chunkX(center) + dx, MinecraftVersionCompat.chunkZ(center) + dz), score
                        )
                     );
                  }
               }
            }

            ranked.sort((left, right) -> Double.compare(left.score(), right.score()));
            List<ChunkPos> ordered = new ArrayList<>(ranked.size());

            for (EarthChunkGenerator.ChunkDetailManager.PrefetchTarget target : ranked) {
               ordered.add(target.pos());
            }

            return ordered;
         }
      }

      private List<ChunkPos> buildSymmetricTargets(ChunkPos center, int radius) {
         int clampedRadius = Math.max(0, radius);
         List<EarthChunkGenerator.ChunkDetailManager.PrefetchTarget> ranked = new ArrayList<>();

         for (int dz = -clampedRadius; dz <= clampedRadius; dz++) {
            for (int dx = -clampedRadius; dx <= clampedRadius; dx++) {
               int chebyshev = Math.max(Math.abs(dx), Math.abs(dz));
               int manhattan = Math.abs(dx) + Math.abs(dz);
               double score = dx == 0 && dz == 0 ? Double.NEGATIVE_INFINITY : chebyshev * 8.0 + manhattan;
               ranked.add(
                  new EarthChunkGenerator.ChunkDetailManager.PrefetchTarget(
                     new ChunkPos(MinecraftVersionCompat.chunkX(center) + dx, MinecraftVersionCompat.chunkZ(center) + dz), score
                  )
               );
            }
         }

         ranked.sort((left, right) -> Double.compare(left.score(), right.score()));
         List<ChunkPos> ordered = new ArrayList<>(ranked.size());

         for (EarthChunkGenerator.ChunkDetailManager.PrefetchTarget target : ranked) {
            ordered.add(target.pos());
         }

         return ordered;
      }

      private record PrefetchTarget(ChunkPos pos, double score) {
      }

      private record PlayerPrefetchState(long chunkKey, EarthChunkGenerator.ChunkDetailManager.PrefetchVector direction, long lastPrefetchGameTime) {
      }

      private record PrefetchVector(double x, double z) {
         private static final EarthChunkGenerator.ChunkDetailManager.PrefetchVector NONE = new EarthChunkGenerator.ChunkDetailManager.PrefetchVector(0.0, 0.0);

         private static EarthChunkGenerator.ChunkDetailManager.PrefetchVector normalized(double x, double z) {
            double length = Math.hypot(x, z);
            return length > 1.0E-4 ? new EarthChunkGenerator.ChunkDetailManager.PrefetchVector(x / length, z / length) : NONE;
         }

         private boolean hasDirection() {
            return Math.abs(this.x) > 1.0E-4 || Math.abs(this.z) > 1.0E-4;
         }

         private double dot(EarthChunkGenerator.ChunkDetailManager.PrefetchVector other) {
            return this.x * other.x + this.z * other.z;
         }
      }
   }

   private static final class TerrainRefinementJob {
      private volatile EarthChunkGenerator.TerrainRefinementJobState state;
      private volatile EarthChunkGenerator.PreparedTerrainRefinement refinement;

      private TerrainRefinementJob(EarthChunkGenerator.TerrainRefinementJobState state) {
         this.state = state;
      }
   }

   private static enum TerrainRefinementJobState {
      QUEUED,
      FETCHING,
      READY,
      APPLIED,
      FAILED,
      STALE;
   }

   private static final class ChunkDetailJob {
      private final long createdNs = EarthChunkGenerator.ChunkDetailPerf.now();
      private volatile EarthChunkGenerator.ChunkDetailJobState state;
      private volatile EarthChunkGenerator.PreparedChunkDetail detail;

      private ChunkDetailJob(EarthChunkGenerator.ChunkDetailJobState state) {
         this.state = state;
      }
   }

   private static enum ChunkDetailJobState {
      QUEUED,
      FETCHING,
      READY,
      APPLIED,
      FAILED,
      STALE;
   }

   private static enum SurfaceMode {
      LEGACY,
      TWO_TIER;
   }

   private static enum FullChunkPhase {
      STRUCTURES_TOTAL("total"),
      STRUCTURES_SUPER("super"),
      STRUCTURES_VILLAGES("villages"),
      STRUCTURES_ABANDONED_CAMPS("abandonedCamps"),
      STRUCTURES_WOODLAND_MANSIONS("woodland"),
      STRUCTURES_STRONGHOLDS("strongholds"),
      STRUCTURES_MINESHAFTS("mineshafts"),
      STRUCTURES_ANCIENT_CITIES("ancientCities"),
      STRUCTURES_TRIAL_CHAMBERS("trialChambers"),
      STRUCTURES_OCEAN_MONUMENTS("monuments"),
      STRUCTURES_OSM_COLLISIONS("osmCollision"),
      FILL_TOTAL("total"),
      FILL_PREFETCH("prefetch"),
      FILL_HEIGHT_GRID("heightGrid"),
      FILL_HEIGHT_GRID_CACHE_HIT("cacheHit"),
      FILL_HEIGHT_GRID_CACHE_MISS("cacheMiss"),
      FILL_WATER_RESOLVE("waterResolve"),
      FILL_COLUMN_RESOLVE("columnResolve"),
      FILL_SHORELINE_BANK_RAMP("shorelineBankRamp"),
      FILL_TERRAIN_REPAIR("terrainRepair"),
      FILL_BUILDING_PREP("buildingPrep"),
      FILL_BUILDING_TERRAIN("buildingTerrain"),
      FILL_BIOME_CACHE("biomeCache"),
      FILL_BIOME_CACHE_KOPPEN_HIT("koppenHit"),
      FILL_BIOME_CACHE_KOPPEN_MISS("koppenMiss"),
      FILL_DETAIL_SCHEDULE("detailSchedule"),
      FILL_DETAIL_TREE_PREP("treePrep"),
      FILL_BLOCKS("blockFill"),
      FILL_BLOCKS_SOLID_SECTIONS("solidSections"),
      FILL_BLOCKS_SOLID_SECTIONS_MAX_INDEX("maxIndex"),
      FILL_BLOCKS_SOLID_SECTIONS_SCAN("scan"),
      FILL_BLOCKS_SOLID_SECTIONS_SECTION_ACCESS("sectionAccess"),
      FILL_BLOCKS_SOLID_SECTIONS_SECTION_WRITE("sectionWrite"),
      FILL_BLOCKS_SOLID_SECTIONS_RECALC("recalc"),
      FILL_BLOCKS_DIRTY_SECTIONS("dirtySections"),
      FILL_BLOCKS_STONE_FILL("stoneFill"),
      FILL_BLOCKS_WATER_FILL("waterFill"),
      FILL_BLOCKS_SURFACE("surface"),
      FILL_BLOCKS_SURFACE_COVER_RESOLVE("coverResolve"),
      FILL_BLOCKS_SURFACE_PALETTE("paletteResolve"),
      FILL_BLOCKS_SURFACE_BADLANDS("badlands"),
      FILL_BLOCKS_SURFACE_BLOCK_WRITES("blockWrites"),
      FILL_BLOCKS_SURFACE_FAST_PATH("fastPath"),
      FILL_BLOCKS_SNOW("snow"),
      FILL_ROADS("roads"),
      FILL_STRUCTURE_CLEARANCE("structureClearance"),
      CARVERS_TOTAL("total"),
      CARVERS_WATER_GUARD("waterGuard"),
      CARVERS_RUNNER("runner"),
      DECORATION_TOTAL("total"),
      DECORATION_SUPER("super"),
      DECORATION_AXOLOTLS("axolotls"),
      DECORATION_TREES("trees"),
      DECORATION_BUILDINGS("buildings"),
      DECORATION_REALTIME_SNOW("realtimeSnow"),
      DECORATION_ROAD_LIGHTS("roadLights"),
      DECORATION_DEFERRED_APPLY("deferredApply"),
      DECORATION_TREES_DEFER_APPLY("deferredTreeApply");

      private final String logId;

      private FullChunkPhase(String logId) {
         this.logId = logId;
      }

      public String logId() {
         return this.logId;
      }
   }

   private static final class FullChunkPerf {
      private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("tellus.debug.fullChunkPerf", "false"));
      private static final boolean TRACE_ENABLED = Boolean.parseBoolean(System.getProperty("tellus.chunkgen.timing", "false"));
      private static final long TRACE_THRESHOLD_NS = TimeUnit.MILLISECONDS.toNanos(
         intProperty("tellus.chunkgen.timingThresholdMs", 0, 0, 600000)
      );
      private static final long LOG_INTERVAL_NS = TimeUnit.SECONDS.toNanos(15L);
      private static final AtomicLong NEXT_LOG_AT_NS = new AtomicLong(System.nanoTime() + LOG_INTERVAL_NS);
      private static final EarthChunkGenerator.FullChunkPhase[] PHASES = EarthChunkGenerator.FullChunkPhase.values();
      private static final LongAdder[] TOTAL_NS = createCounters();
      private static final LongAdder[] CALLS = createCounters();
      private static final ThreadLocal<EarthChunkGenerator.FullChunkTrace> ACTIVE_TRACE = new ThreadLocal<>();

      private static long now() {
         return ENABLED || TRACE_ENABLED ? System.nanoTime() : 0L;
      }

      private static long elapsedSince(long startNs) {
         return (ENABLED || TRACE_ENABLED) && startNs != 0L ? System.nanoTime() - startNs : 0L;
      }

      private static void record(EarthChunkGenerator.FullChunkPhase phase, long totalNs) {
         if (phase == null) {
            return;
         }

         if (ENABLED) {
            int index = phase.ordinal();
            CALLS[index].increment();
            if (totalNs > 0L) {
               TOTAL_NS[index].add(totalNs);
            }

            maybeLog();
         }

         EarthChunkGenerator.FullChunkTrace trace = ACTIVE_TRACE.get();
         if (trace != null) {
            trace.record(phase, totalNs);
         }
      }

      private static void recordCount(EarthChunkGenerator.FullChunkPhase phase, int count) {
         if (phase != null && count > 0) {
            if (ENABLED) {
               CALLS[phase.ordinal()].add(count);
               maybeLog();
            }

            EarthChunkGenerator.FullChunkTrace trace = ACTIVE_TRACE.get();
            if (trace != null) {
               trace.recordCount(phase, count);
            }
         }
      }

      private static EarthChunkGenerator.FullChunkTrace beginTrace(String stage, ChunkPos pos) {
         if (!TRACE_ENABLED) {
            return null;
         } else {
            EarthChunkGenerator.FullChunkTrace trace = new EarthChunkGenerator.FullChunkTrace(stage, pos, ACTIVE_TRACE.get());
            ACTIVE_TRACE.set(trace);
            return trace;
         }
      }

      private static void finishTrace(EarthChunkGenerator.FullChunkTrace trace, String status, Throwable throwable) {
         if (trace != null) {
            try {
               trace.log(status, throwable);
            } finally {
               if (trace.previous() == null) {
                  ACTIVE_TRACE.remove();
               } else {
                  ACTIVE_TRACE.set(trace.previous());
               }
            }
         }
      }

      private static LongAdder[] createCounters() {
         LongAdder[] counters = new LongAdder[PHASES.length];

         for (int i = 0; i < counters.length; i++) {
            counters[i] = new LongAdder();
         }

         return counters;
      }

      private static void maybeLog() {
         long now = System.nanoTime();
         long next = NEXT_LOG_AT_NS.get();
         if (now >= next && NEXT_LOG_AT_NS.compareAndSet(next, now + LOG_INTERVAL_NS)) {
            logAndReset();
         }
      }

      private static void logAndReset() {
         long[] totals = new long[PHASES.length];
         long[] calls = new long[PHASES.length];

         for (int i = 0; i < PHASES.length; i++) {
            totals[i] = TOTAL_NS[i].sumThenReset();
            calls[i] = CALLS[i].sumThenReset();
         }

         logGroup(
            "structures",
            totals,
            calls,
            EarthChunkGenerator.FullChunkPhase.STRUCTURES_TOTAL,
            EarthChunkGenerator.FullChunkPhase.STRUCTURES_SUPER,
            EarthChunkGenerator.FullChunkPhase.STRUCTURES_VILLAGES,
            EarthChunkGenerator.FullChunkPhase.STRUCTURES_ABANDONED_CAMPS,
            EarthChunkGenerator.FullChunkPhase.STRUCTURES_WOODLAND_MANSIONS,
            EarthChunkGenerator.FullChunkPhase.STRUCTURES_STRONGHOLDS,
            EarthChunkGenerator.FullChunkPhase.STRUCTURES_MINESHAFTS,
            EarthChunkGenerator.FullChunkPhase.STRUCTURES_ANCIENT_CITIES,
            EarthChunkGenerator.FullChunkPhase.STRUCTURES_TRIAL_CHAMBERS,
            EarthChunkGenerator.FullChunkPhase.STRUCTURES_OCEAN_MONUMENTS,
            EarthChunkGenerator.FullChunkPhase.STRUCTURES_OSM_COLLISIONS
         );
         logGroup(
            "fill",
            totals,
            calls,
            EarthChunkGenerator.FullChunkPhase.FILL_TOTAL,
            EarthChunkGenerator.FullChunkPhase.FILL_PREFETCH,
            EarthChunkGenerator.FullChunkPhase.FILL_HEIGHT_GRID,
            EarthChunkGenerator.FullChunkPhase.FILL_WATER_RESOLVE,
            EarthChunkGenerator.FullChunkPhase.FILL_COLUMN_RESOLVE,
            EarthChunkGenerator.FullChunkPhase.FILL_SHORELINE_BANK_RAMP,
            EarthChunkGenerator.FullChunkPhase.FILL_TERRAIN_REPAIR,
            EarthChunkGenerator.FullChunkPhase.FILL_BUILDING_PREP,
            EarthChunkGenerator.FullChunkPhase.FILL_BUILDING_TERRAIN,
            EarthChunkGenerator.FullChunkPhase.FILL_BIOME_CACHE,
            EarthChunkGenerator.FullChunkPhase.FILL_DETAIL_SCHEDULE,
            EarthChunkGenerator.FullChunkPhase.FILL_DETAIL_TREE_PREP,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS,
            EarthChunkGenerator.FullChunkPhase.FILL_ROADS,
            EarthChunkGenerator.FullChunkPhase.FILL_STRUCTURE_CLEARANCE
        );
         logGroup(
            "fill.heightGrid.detail",
            totals,
            calls,
            EarthChunkGenerator.FullChunkPhase.FILL_HEIGHT_GRID,
            EarthChunkGenerator.FullChunkPhase.FILL_HEIGHT_GRID_CACHE_HIT,
            EarthChunkGenerator.FullChunkPhase.FILL_HEIGHT_GRID_CACHE_MISS
         );
         logGroup(
            "fill.biomeCache.detail",
            totals,
            calls,
            EarthChunkGenerator.FullChunkPhase.FILL_BIOME_CACHE,
            EarthChunkGenerator.FullChunkPhase.FILL_BIOME_CACHE_KOPPEN_HIT,
            EarthChunkGenerator.FullChunkPhase.FILL_BIOME_CACHE_KOPPEN_MISS
         );
         logGroup(
            "fill.blockFill",
            totals,
            calls,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SOLID_SECTIONS,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_DIRTY_SECTIONS,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_STONE_FILL,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_WATER_FILL,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SURFACE,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SNOW
        );
         logGroup(
            "fill.blockFill.solidSections",
            totals,
            calls,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SOLID_SECTIONS,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SOLID_SECTIONS_MAX_INDEX,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SOLID_SECTIONS_SCAN,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SOLID_SECTIONS_SECTION_ACCESS,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SOLID_SECTIONS_SECTION_WRITE,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SOLID_SECTIONS_RECALC
         );
         logGroup(
            "fill.blockFill.surface",
            totals,
            calls,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SURFACE,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SURFACE_COVER_RESOLVE,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SURFACE_PALETTE,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SURFACE_BADLANDS,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SURFACE_BLOCK_WRITES,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SURFACE_FAST_PATH
        );
         logGroup(
            "carvers",
            totals,
            calls,
            EarthChunkGenerator.FullChunkPhase.CARVERS_TOTAL,
            EarthChunkGenerator.FullChunkPhase.CARVERS_WATER_GUARD,
            EarthChunkGenerator.FullChunkPhase.CARVERS_RUNNER
         );
         logGroup(
            "decoration",
            totals,
            calls,
            EarthChunkGenerator.FullChunkPhase.DECORATION_TOTAL,
            EarthChunkGenerator.FullChunkPhase.DECORATION_SUPER,
            EarthChunkGenerator.FullChunkPhase.DECORATION_AXOLOTLS,
            EarthChunkGenerator.FullChunkPhase.DECORATION_TREES,
            EarthChunkGenerator.FullChunkPhase.DECORATION_BUILDINGS,
            EarthChunkGenerator.FullChunkPhase.DECORATION_REALTIME_SNOW,
            EarthChunkGenerator.FullChunkPhase.DECORATION_ROAD_LIGHTS,
            EarthChunkGenerator.FullChunkPhase.DECORATION_DEFERRED_APPLY,
            EarthChunkGenerator.FullChunkPhase.DECORATION_TREES_DEFER_APPLY
        );
      }

      private static void logGroup(String label, long[] totals, long[] calls, EarthChunkGenerator.FullChunkPhase... phases) {
         if (phases.length != 0) {
            long rootCalls = calls[phases[0].ordinal()];
            long rootTotal = totals[phases[0].ordinal()];
            if (rootCalls > 0L || rootTotal > 0L) {
               StringBuilder message = new StringBuilder("Full chunk perf 15s [").append(label).append("]:");

               for (EarthChunkGenerator.FullChunkPhase phase : phases) {
                  int index = phase.ordinal();
                  long total = totals[index];
                  long callCount = calls[index];
                  if (callCount > 0L || total > 0L) {
                     message
                        .append(' ')
                        .append(phase.logId())
                        .append('=')
                        .append(toMillis(total))
                        .append("ms/")
                        .append(callCount);
                  }
               }

               Tellus.LOGGER.info(message.toString());
            }
         }
      }

      private static String toMillis(long nanos) {
         return nanos <= 0L ? "0.00" : String.format(Locale.ROOT, "%.2f", nanos / 1000000.0);
      }
   }

   private static final class FullChunkTrace {
      private final String stage;
      private final ChunkPos pos;
      private final EarthChunkGenerator.FullChunkTrace previous;
      private final long startNs = System.nanoTime();
      private final LinkedHashMap<EarthChunkGenerator.FullChunkPhase, Long> phaseNanos = new LinkedHashMap<>();
      private final LinkedHashMap<EarthChunkGenerator.FullChunkPhase, Long> counts = new LinkedHashMap<>();

      private FullChunkTrace(String stage, ChunkPos pos, EarthChunkGenerator.FullChunkTrace previous) {
         this.stage = stage;
         this.pos = pos;
         this.previous = previous;
      }

      private EarthChunkGenerator.FullChunkTrace previous() {
         return this.previous;
      }

      private void record(EarthChunkGenerator.FullChunkPhase phase, long nanos) {
         if (nanos > 0L) {
            this.phaseNanos.merge(phase, nanos, Long::sum);
         }
      }

      private void recordCount(EarthChunkGenerator.FullChunkPhase phase, int count) {
         if (count > 0) {
            this.counts.merge(phase, (long)count, Long::sum);
         }
      }

      private void log(String status, Throwable throwable) {
         long totalNs = System.nanoTime() - this.startNs;
         if (!"failed".equals(status) && totalNs < EarthChunkGenerator.FullChunkPerf.TRACE_THRESHOLD_NS) {
            return;
         }

         StringBuilder builder = new StringBuilder(256);
         builder.append("Full chunk timing status=").append(status);
         builder.append(" stage=").append(this.stage);
         builder.append(" chunk=[")
            .append(MinecraftVersionCompat.chunkX(this.pos))
            .append(", ")
            .append(MinecraftVersionCompat.chunkZ(this.pos))
            .append(']');
         builder.append(" total=").append(formatMillis(totalNs));
         if (!this.phaseNanos.isEmpty()) {
            builder.append(" phases={");
            boolean first = true;
            for (Map.Entry<EarthChunkGenerator.FullChunkPhase, Long> entry : this.phaseNanos.entrySet()) {
               if (!first) {
                  builder.append(", ");
               }

               builder.append(entry.getKey().logId()).append('=').append(formatMillis(entry.getValue()));
               first = false;
            }

            builder.append('}');
         }

         if (!this.counts.isEmpty()) {
            builder.append(" counts={");
            boolean first = true;
            for (Map.Entry<EarthChunkGenerator.FullChunkPhase, Long> entry : this.counts.entrySet()) {
               if (!first) {
                  builder.append(", ");
               }

               builder.append(entry.getKey().logId()).append('=').append(entry.getValue());
               first = false;
            }

            builder.append('}');
         }

         if (throwable == null) {
            Tellus.LOGGER.info(builder.toString());
         } else {
            Tellus.LOGGER.warn(builder.toString(), throwable);
         }
      }

      private static String formatMillis(long nanos) {
         return String.format(Locale.ROOT, "%.3fms", (double)nanos / 1000000.0);
      }
   }

   private static final class SolidSectionFillProfiler {
      private long maxIndexNs;
      private long scanNs;
      private long sectionAccessNs;
      private long sectionWriteNs;
      private long recalcNs;
   }

   private static final class SurfaceApplyProfiler {
      private long paletteResolveNs;
      private long badlandsNs;
      private long blockWriteNs;
      private int fastPathCount;
   }

   private static final class TerrainStreamingPerf {
      private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("tellus.debug.chunkDetailPerf", "false"));
      private static final long LOG_INTERVAL_NS = TimeUnit.SECONDS.toNanos(15L);
      private static final AtomicLong NEXT_LOG_AT_NS = new AtomicLong(System.nanoTime() + LOG_INTERVAL_NS);
      private static final LongAdder SHELL_CHUNKS = new LongAdder();
      private static final LongAdder SHELL_HEIGHT_MISSES = new LongAdder();
      private static final LongAdder SHELL_COVER_MISSES = new LongAdder();
      private static final LongAdder SHELL_VISUAL_MISSES = new LongAdder();
      private static final LongAdder SHELL_WATER_FALLBACKS = new LongAdder();
      private static final LongAdder SHELL_HEIGHT_FALLBACKS = new LongAdder();
      private static final LongAdder PREFETCH_QUEUE_REJECTIONS = new LongAdder();
      private static final LongAdder REFINEMENT_QUEUED = new LongAdder();
      private static final LongAdder REFINEMENT_APPLIES = new LongAdder();
      private static final LongAdder REFINEMENT_STALE_DROPS = new LongAdder();
      private static final LongAdder DETAIL_DELAYS = new LongAdder();

      private static void recordShellBuild(EarthChunkGenerator.TerrainWarmupTicket ticket) {
         if (ENABLED && ticket != null) {
            SHELL_CHUNKS.increment();
            if (ticket.heightGridMisses() > 0) {
               SHELL_HEIGHT_MISSES.add(ticket.heightGridMisses());
            }

            if (ticket.coverMisses() > 0) {
               SHELL_COVER_MISSES.add(ticket.coverMisses());
            }

            if (ticket.visualCoverMisses() > 0) {
               SHELL_VISUAL_MISSES.add(ticket.visualCoverMisses());
            }

            if (ticket.waterFallback()) {
               SHELL_WATER_FALLBACKS.increment();
            }

            if (ticket.usedHeightFallback()) {
               SHELL_HEIGHT_FALLBACKS.increment();
            }

            maybeLog();
         }
      }

      private static void recordRefinementApplied() {
         if (ENABLED) {
            REFINEMENT_APPLIES.increment();
            maybeLog();
         }
      }

      private static void recordRefinementQueued() {
         if (ENABLED) {
            REFINEMENT_QUEUED.increment();
            maybeLog();
         }
      }

      private static void recordRefinementStaleDrop() {
         if (ENABLED) {
            REFINEMENT_STALE_DROPS.increment();
            maybeLog();
         }
      }

      private static void recordPrefetchQueueRejection() {
         if (ENABLED) {
            PREFETCH_QUEUE_REJECTIONS.increment();
            maybeLog();
         }
      }

      private static void recordDetailDelayedByRefinement() {
         if (ENABLED) {
            DETAIL_DELAYS.increment();
            maybeLog();
         }
      }

      private static void maybeLog() {
         long now = System.nanoTime();
         long next = NEXT_LOG_AT_NS.get();
         if (now >= next && NEXT_LOG_AT_NS.compareAndSet(next, now + LOG_INTERVAL_NS)) {
            Tellus.LOGGER.info(
               "Terrain streaming perf 15s: shell(chunks={},heightMisses={},coverMisses={},visualMisses={},waterFallbacks={},heightFallbacks={},chunkThreadElevationDiskOpens=0,chunkThreadCoverDiskOpens=0,prefetchQueueRejections={}) refinement(queued={},applied={},staleDrops={},detailDelays={})",
               new Object[]{
                  SHELL_CHUNKS.sumThenReset(),
                  SHELL_HEIGHT_MISSES.sumThenReset(),
                  SHELL_COVER_MISSES.sumThenReset(),
                  SHELL_VISUAL_MISSES.sumThenReset(),
                  SHELL_WATER_FALLBACKS.sumThenReset(),
                  SHELL_HEIGHT_FALLBACKS.sumThenReset(),
                  PREFETCH_QUEUE_REJECTIONS.sumThenReset(),
                  REFINEMENT_QUEUED.sumThenReset(),
                  REFINEMENT_APPLIES.sumThenReset(),
                  REFINEMENT_STALE_DROPS.sumThenReset(),
                  DETAIL_DELAYS.sumThenReset()
               }
            );
         }
      }
   }

   private static final class ChunkDetailPerf {
      private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("tellus.debug.chunkDetailPerf", "false"));
      private static final long LOG_INTERVAL_NS = TimeUnit.SECONDS.toNanos(15L);
      private static final AtomicLong NEXT_LOG_AT_NS = new AtomicLong(System.nanoTime() + LOG_INTERVAL_NS);
      private static final LongAdder BASE_TERRAIN_NS = new LongAdder();
      private static final LongAdder BASE_TERRAIN_CALLS = new LongAdder();
      private static final LongAdder DETAIL_JOB_NS = new LongAdder();
      private static final LongAdder DETAIL_JOB_CALLS = new LongAdder();
      private static final LongAdder DETAIL_APPLY_NS = new LongAdder();
      private static final LongAdder DETAIL_APPLY_CALLS = new LongAdder();
      private static final LongAdder SKIPPED_BLOCKING_FALLBACKS = new LongAdder();
      private static final LongAdder STALE_DROPS = new LongAdder();
      private static final LongAdder FAILURES = new LongAdder();
      private static final AtomicLong MAX_QUEUE_DEPTH = new AtomicLong();

      private static long now() {
         return ENABLED ? System.nanoTime() : 0L;
      }

      private static long elapsedSince(long startNs) {
         return ENABLED && startNs != 0L ? System.nanoTime() - startNs : 0L;
      }

      private static void recordBaseTerrain(long totalNs) {
         if (ENABLED) {
            BASE_TERRAIN_CALLS.increment();
            if (totalNs > 0L) {
               BASE_TERRAIN_NS.add(totalNs);
            }

            maybeLog();
         }
      }

      private static void recordDetailJob(long totalNs) {
         if (ENABLED) {
            DETAIL_JOB_CALLS.increment();
            if (totalNs > 0L) {
               DETAIL_JOB_NS.add(totalNs);
            }

            maybeLog();
         }
      }

      private static void recordDetailApply(long totalNs) {
         if (ENABLED) {
            DETAIL_APPLY_CALLS.increment();
            if (totalNs > 0L) {
               DETAIL_APPLY_NS.add(totalNs);
            }

            maybeLog();
         }
      }

      private static void recordSkippedBlockingFallback() {
         if (ENABLED) {
            SKIPPED_BLOCKING_FALLBACKS.increment();
            maybeLog();
         }
      }

      private static void recordStaleDrop() {
         if (ENABLED) {
            STALE_DROPS.increment();
            maybeLog();
         }
      }

      private static void recordFailure() {
         if (ENABLED) {
            FAILURES.increment();
            maybeLog();
         }
      }

      private static void recordQueueDepth(int queueDepth) {
         if (ENABLED) {
            MAX_QUEUE_DEPTH.accumulateAndGet(queueDepth, Math::max);
            maybeLog();
         }
      }

      private static void maybeLog() {
         long now = System.nanoTime();
         long next = NEXT_LOG_AT_NS.get();
         if (now >= next && NEXT_LOG_AT_NS.compareAndSet(next, now + LOG_INTERVAL_NS)) {
            logAndReset();
         }
      }

      private static void logAndReset() {
         Tellus.LOGGER.info(
            "Chunk detail perf 15s: baseTerrain(total={}ms,calls={}) detailJob(total={}ms,calls={}) detailApply(total={}ms,calls={}) skippedFallbacks={} staleDrops={} failures={} maxQueueDepth={}",
            new Object[]{
               toMillis(BASE_TERRAIN_NS.sumThenReset()),
               BASE_TERRAIN_CALLS.sumThenReset(),
               toMillis(DETAIL_JOB_NS.sumThenReset()),
               DETAIL_JOB_CALLS.sumThenReset(),
               toMillis(DETAIL_APPLY_NS.sumThenReset()),
               DETAIL_APPLY_CALLS.sumThenReset(),
               SKIPPED_BLOCKING_FALLBACKS.sumThenReset(),
               STALE_DROPS.sumThenReset(),
               FAILURES.sumThenReset(),
               MAX_QUEUE_DEPTH.getAndSet(0L)
            }
         );
      }

      private static String toMillis(long nanos) {
         return nanos <= 0L ? "0.00" : String.format(Locale.ROOT, "%.2f", nanos / 1000000.0);
      }
   }

   private record RoadColumnSample(int terrainSurface, int waterSurface, boolean hasWater) {
      private int roadSurface() {
         return this.hasWater ? Math.max(this.terrainSurface, this.waterSurface) : this.terrainSurface;
      }
   }

   private static final class FullChunkOceanBeachCache {
      private final int minX;
      private final int minZ;
      private final byte[] oceanDistances;
      private final boolean hasOcean;

      private FullChunkOceanBeachCache(int minX, int minZ, byte[] oceanDistances, boolean hasOcean) {
         this.minX = minX;
         this.minZ = minZ;
         this.oceanDistances = oceanDistances;
         this.hasOcean = hasOcean;
      }

      private static EarthChunkGenerator.FullChunkOceanBeachCache disabled(int minX, int minZ) {
         byte[] oceanDistances = new byte[CHUNK_AREA];
         Arrays.fill(oceanDistances, (byte)-1);
         return new EarthChunkGenerator.FullChunkOceanBeachCache(minX, minZ, oceanDistances, false);
      }

      private boolean hasOcean() {
         return this.hasOcean;
      }

      private int distanceToOcean(int worldX, int worldZ) {
         int localX = worldX - this.minX;
         int localZ = worldZ - this.minZ;
         if (localX >= 0 && localX < CHUNK_SIDE && localZ >= 0 && localZ < CHUNK_SIDE) {
            return this.oceanDistances[chunkIndex(localX, localZ)];
         } else {
            return -1;
         }
      }
   }

   private record ShorelineContext(MountainSurfaceRules.ShorelineKind kind, int distanceToShore, boolean shallowWater) {
      private static final EarthChunkGenerator.ShorelineContext NONE = new EarthChunkGenerator.ShorelineContext(
         MountainSurfaceRules.ShorelineKind.NONE, Integer.MAX_VALUE, false
      );
   }

   private record RoadWidths(int main, int normal, int dirt) {
   }

   private static final class BuildingGroupScratch {
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

   private record RasterizedBuildingFeature(
      OsmBuildingFeature feature,
      String groupId,
      boolean[] occupiedMask,
      int[] occupiedIndices,
      boolean[] boundary,
      int[] boundaryDistance
   ) {
      private boolean boundary(int index) {
         return this.boundary[index];
      }

      private int boundaryDistance(int index) {
         return this.boundaryDistance[index];
      }
   }

   private record AnalyzedBuildingFeature(EarthChunkGenerator.RasterizedBuildingFeature rasterized, IntArrayList groundSamples) {
   }

   private record RoadLightAnchor(int localX, int localZ, int baseY, int index) {
   }

   private static final class PreparedChunkBuildings {
      private final List<EarthChunkGenerator.PreparedBuildingFeature> features = new ArrayList<>();
      private final List<List<EarthChunkGenerator.BuildingColumnSpan>> columnSpans = new ArrayList<>(CHUNK_AREA);
      private final int[] flattenedTerrain = new int[CHUNK_AREA];
      private final boolean[] treeSuppression = new boolean[CHUNK_AREA];

      private PreparedChunkBuildings() {
         for (int i = 0; i < CHUNK_AREA; i++) {
            this.columnSpans.add(null);
         }

         Arrays.fill(this.flattenedTerrain, Integer.MIN_VALUE);
      }

      private boolean isEmpty() {
         return this.features.isEmpty();
      }

      private List<EarthChunkGenerator.PreparedBuildingFeature> features() {
         return this.features;
      }

      private int flattenedTerrainSurface(int index) {
         return this.flattenedTerrain[index];
      }

      private boolean suppressesTrees(int localX, int localZ) {
         return this.treeSuppression[chunkIndex(localX, localZ)];
      }

      private boolean intersectsRoad(int localX, int localZ, int y) {
         List<EarthChunkGenerator.BuildingColumnSpan> spans = this.columnSpans.get(chunkIndex(localX, localZ));
         if (spans == null) {
            return false;
         } else {
            for (EarthChunkGenerator.BuildingColumnSpan span : spans) {
               if (span.contains(y)) {
                  return true;
               }
            }

            return false;
         }
      }

      private boolean intersectsSpan(int localX, int localZ, int minY, int maxY) {
         List<EarthChunkGenerator.BuildingColumnSpan> spans = this.columnSpans.get(chunkIndex(localX, localZ));
         if (spans == null) {
            return false;
         } else {
            for (EarthChunkGenerator.BuildingColumnSpan span : spans) {
               if (span.endY() >= minY && span.startY() <= maxY) {
                  return true;
               }
            }

            return false;
         }
      }

      private void addFeature(EarthChunkGenerator.PreparedBuildingFeature feature) {
         this.features.add(feature);

         for (int index : feature.occupiedIndices()) {
            int spanStart = feature.groundContact() ? feature.floorY() - 1 : feature.floorY();
            this.addSpan(index, spanStart, feature.columnTopY(index));
            if (feature.groundContact()) {
               int flattened = feature.floorY() - 1;
               if (flattened > this.flattenedTerrain[index]) {
                  this.flattenedTerrain[index] = flattened;
               }

               this.treeSuppression[index] = true;
            }
         }
      }

      private void addSpan(int index, int startY, int endY) {
         if (endY < startY) {
            return;
         }

         List<EarthChunkGenerator.BuildingColumnSpan> spans = this.columnSpans.get(index);
         if (spans == null) {
            spans = new ArrayList<>(2);
            spans.add(new EarthChunkGenerator.BuildingColumnSpan(startY, endY));
            this.columnSpans.set(index, spans);
         } else {
            int mergedStart = startY;
            int mergedEnd = endY;
            int insertAt = spans.size();

            for (int i = 0; i < spans.size(); i++) {
               EarthChunkGenerator.BuildingColumnSpan span = spans.get(i);
               if (mergedEnd + 1 < span.startY()) {
                  insertAt = i;
                  break;
               }

               if (mergedStart <= span.endY() + 1 && mergedEnd + 1 >= span.startY()) {
                  mergedStart = Math.min(mergedStart, span.startY());
                  mergedEnd = Math.max(mergedEnd, span.endY());
                  spans.remove(i--);
                  insertAt = i + 1;
               }
            }

            spans.add(insertAt, new EarthChunkGenerator.BuildingColumnSpan(mergedStart, mergedEnd));
         }
      }
   }

   private static final class PreparedChunkRoadLights {
      private final List<EarthChunkGenerator.PreparedRoadLight> lights = new ArrayList<>();

      private boolean isEmpty() {
         return this.lights.isEmpty();
      }

      private List<EarthChunkGenerator.PreparedRoadLight> lights() {
         return this.lights;
      }

      private void add(EarthChunkGenerator.PreparedRoadLight light) {
         this.lights.add(light);
      }
   }

   private record PreparedBuildingFeature(
      boolean[] occupied,
      int[] occupiedIndices,
      boolean[] boundary,
      int[] boundaryDistance,
      int[] columnTopY,
      BuildingBlueprint blueprint,
      boolean groundContact,
      TellusBuildingInteriors interior
   ) {
      private int boundaryDistance(int index) {
         return this.boundaryDistance[index];
      }

      private boolean occupied(int index) {
         return this.occupied[index];
      }

      private boolean boundary(int index) {
         return this.boundary[index];
      }

      private int columnTopY(int index) {
         return this.columnTopY[index];
      }

      private int floorY() {
         return this.blueprint.floorY();
      }
   }

   private record BuildingColumnSpan(int startY, int endY) {
      private boolean contains(int y) {
         return y >= this.startY && y <= this.endY;
      }
   }

   private record PreparedRoadLight(StreetLightPlanner.Lamp lamp, int baseY) {
   }

   private record UndergroundStructureEnvelope(int lowestSurfaceY) {
   }

   private record BuildingBoundaryInfo(boolean[] boundary, int[] boundaryDistance) {
   }

   /**
    * @param landformSurface loose debris or ice placed by a landform rule, which the
    *    steep-slope exposed-rock rule leaves in place
    */
   private record SurfacePalette( BlockState top,  BlockState underwaterTop,  BlockState filler, int depth, boolean landformSurface) {
      private SurfacePalette(BlockState top, BlockState underwaterTop, BlockState filler, int depth) {
         this(top, underwaterTop, filler, depth, false);
      }

      static EarthChunkGenerator.SurfacePalette landform(MountainSurfaceRules.MountainForm form, int worldX, int worldZ) {
         BlockState top = mountainMaterialState(MountainSurfaceRules.formSurfaceMaterial(form, worldX, worldZ));
         boolean looseDebris = MountainSurfaceRules.isLooseDebrisForm(form);
         return new EarthChunkGenerator.SurfacePalette(top, top, EarthChunkGenerator.STONE_STATE, looseDebris ? 4 : 12, looseDebris);
      }

      static EarthChunkGenerator.SurfacePalette glacier(BlockState top) {
         return new EarthChunkGenerator.SurfacePalette(top, top, EarthChunkGenerator.PACKED_ICE_STATE, 5, true);
      }

      static EarthChunkGenerator.SurfacePalette defaultOverworld() {
         return new EarthChunkGenerator.SurfacePalette(EarthChunkGenerator.GRASS_BLOCK_STATE, EarthChunkGenerator.DIRT_STATE, EarthChunkGenerator.DIRT_STATE, 4);
      }

      static EarthChunkGenerator.SurfacePalette mushroomFields() {
         return new EarthChunkGenerator.SurfacePalette(
            EarthChunkGenerator.MYCELIUM_STATE, EarthChunkGenerator.DIRT_STATE, EarthChunkGenerator.DIRT_STATE, 4
         );
      }

      static EarthChunkGenerator.SurfacePalette deepslate(int depth) {
         return new EarthChunkGenerator.SurfacePalette(
            EarthChunkGenerator.DEEPSLATE_STATE,
            EarthChunkGenerator.DEEPSLATE_STATE,
            EarthChunkGenerator.DEEPSLATE_STATE,
            depth
         );
      }

      static EarthChunkGenerator.SurfacePalette steppe() {
         return new EarthChunkGenerator.SurfacePalette(
            EarthChunkGenerator.COARSE_DIRT_STATE, EarthChunkGenerator.COARSE_DIRT_STATE, EarthChunkGenerator.DIRT_STATE, 4
         );
      }

      static EarthChunkGenerator.SurfacePalette podzolic() {
         return new EarthChunkGenerator.SurfacePalette(EarthChunkGenerator.PODZOL_STATE, EarthChunkGenerator.PODZOL_STATE, EarthChunkGenerator.DIRT_STATE, 4);
      }

      static EarthChunkGenerator.SurfacePalette rooted() {
         return new EarthChunkGenerator.SurfacePalette(
            EarthChunkGenerator.ROOTED_DIRT_STATE, EarthChunkGenerator.ROOTED_DIRT_STATE, EarthChunkGenerator.DIRT_STATE, 4
         );
      }

      static EarthChunkGenerator.SurfacePalette mossy() {
         return new EarthChunkGenerator.SurfacePalette(
            EarthChunkGenerator.MOSS_BLOCK_STATE, EarthChunkGenerator.MOSS_BLOCK_STATE, EarthChunkGenerator.DIRT_STATE, 4
         );
      }

      static EarthChunkGenerator.SurfacePalette alpineMeadow() {
         return new EarthChunkGenerator.SurfacePalette(
            EarthChunkGenerator.GRASS_BLOCK_STATE, EarthChunkGenerator.COARSE_DIRT_STATE, EarthChunkGenerator.COARSE_DIRT_STATE, 4
         );
      }

      static EarthChunkGenerator.SurfacePalette snowStreak(BlockState filler) {
         return new EarthChunkGenerator.SurfacePalette(EarthChunkGenerator.SNOW_BLOCK_STATE, EarthChunkGenerator.SNOW_BLOCK_STATE, filler, 5);
      }

      static EarthChunkGenerator.SurfacePalette desert() {
         return new EarthChunkGenerator.SurfacePalette(EarthChunkGenerator.SAND_STATE, EarthChunkGenerator.SAND_STATE, EarthChunkGenerator.SANDSTONE_STATE, 5);
      }

      static EarthChunkGenerator.SurfacePalette badlands(BlockState top) {
         return new EarthChunkGenerator.SurfacePalette(
            top, EarthChunkGenerator.RED_SAND_STATE, EarthChunkGenerator.TERRACOTTA_STATE, 4
         );
      }

      static EarthChunkGenerator.SurfacePalette beach() {
         return new EarthChunkGenerator.SurfacePalette(EarthChunkGenerator.SAND_STATE, EarthChunkGenerator.SAND_STATE, EarthChunkGenerator.SAND_STATE, 4);
      }

      static EarthChunkGenerator.SurfacePalette ocean( BlockState top) {
         return new EarthChunkGenerator.SurfacePalette(top, top, top, 4);
      }

      static EarthChunkGenerator.SurfacePalette snowy() {
         return new EarthChunkGenerator.SurfacePalette(
            EarthChunkGenerator.SNOW_BLOCK_STATE, EarthChunkGenerator.SNOW_BLOCK_STATE, EarthChunkGenerator.DIRT_STATE, 4
         );
      }

      static EarthChunkGenerator.SurfacePalette swamp() {
         return new EarthChunkGenerator.SurfacePalette(EarthChunkGenerator.GRASS_BLOCK_STATE, EarthChunkGenerator.DIRT_STATE, EarthChunkGenerator.DIRT_STATE, 4);
      }

      static EarthChunkGenerator.SurfacePalette wetland() {
         return new EarthChunkGenerator.SurfacePalette(EarthChunkGenerator.MUD_STATE, EarthChunkGenerator.MUD_STATE, EarthChunkGenerator.PACKED_MUD_STATE, 4);
      }

      static EarthChunkGenerator.SurfacePalette mangrove() {
         return new EarthChunkGenerator.SurfacePalette(EarthChunkGenerator.MUD_STATE, EarthChunkGenerator.MUD_STATE, EarthChunkGenerator.DIRT_STATE, 4);
      }

      static EarthChunkGenerator.SurfacePalette stonyPeaks() {
         return new EarthChunkGenerator.SurfacePalette(EarthChunkGenerator.STONE_STATE, EarthChunkGenerator.STONE_STATE, EarthChunkGenerator.STONE_STATE, 12);
      }

      static EarthChunkGenerator.SurfacePalette gravelly() {
         return new EarthChunkGenerator.SurfacePalette(EarthChunkGenerator.GRAVEL_STATE, EarthChunkGenerator.GRAVEL_STATE, EarthChunkGenerator.STONE_STATE, 4);
      }
   }

   private static final class WaterChunkCache {
      private int chunkX = Integer.MIN_VALUE;
      private int chunkZ = Integer.MIN_VALUE;
      private WaterSurfaceResolver.WaterChunkData data;

      private boolean matches(ChunkPos pos) {
         return this.data != null
            && this.chunkX == MinecraftVersionCompat.chunkX(pos)
            && this.chunkZ == MinecraftVersionCompat.chunkZ(pos);
      }

      private WaterSurfaceResolver.WaterChunkData data() {
         return this.data;
      }

      private void update(ChunkPos pos, WaterSurfaceResolver.WaterChunkData data) {
         this.chunkX = MinecraftVersionCompat.chunkX(pos);
         this.chunkZ = MinecraftVersionCompat.chunkZ(pos);
         this.data = data;
      }
   }
}
