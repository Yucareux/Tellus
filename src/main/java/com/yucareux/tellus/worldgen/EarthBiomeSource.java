package com.yucareux.tellus.worldgen;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yucareux.tellus.world.data.biome.BiomeClassification;
import com.yucareux.tellus.world.data.biome.BiomeClassificationProviders;
import com.yucareux.tellus.world.data.cover.TellusLandCoverSource;
import com.yucareux.tellus.world.data.elevation.TellusElevationSource;
import com.yucareux.tellus.world.data.koppen.TellusKoppenSource;
import com.yucareux.tellus.world.data.ocean.OisstOceanClimateSource;
import com.yucareux.tellus.worldgen.caves.TellusCaveBiomeDepthPolicy;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate.Sampler;

public final class EarthBiomeSource extends BiomeSource {
   public static final MapCodec<EarthBiomeSource> CODEC = RecordCodecBuilder.mapCodec(
      instance -> instance.group(
            RegistryOps.retrieveGetter(Registries.BIOME), EarthGeneratorSettings.CODEC.fieldOf("settings").forGetter(EarthBiomeSource::settings)
         )
         .apply(instance, EarthBiomeSource::new)
   );
   private static final int ESA_TREE_COVER = 10;
   private static final int ESA_GRASSLAND = 30;
   private static final int ESA_CROPLAND = 40;
   private static final int ESA_SNOW_ICE = 70;
   private static final int ESA_WATER = 80;
   private static final int ESA_BARE = 60;
   private static final int ESA_MANGROVES = 95;
   private static final int ESA_NO_DATA = 0;
   private static final int LUSH_MIN_DEPTH = 12;
   private static final int DRIPSTONE_MIN_DEPTH = 16;
   private static final int CAVE_BIOME_GRID = 48;
   private static final int CAVE_BIOME_Y_GRID = 32;
   private static final int DEEP_DARK_GRID = 96;
   private static final int DEEP_DARK_Y_GRID = 48;
   private static final int STRUCTURE_BIOME_PROBE_CEILING_ABOVE_MIN_Y = 64;
   private static final double MAX_CAVE_BIOME_CHANCE = 0.55;
   private static final double DEEP_OCEAN_DEPTH_METERS = 200.0;
   private static final double FROZEN_OCEAN_SST_C = -1.5;
   private static final double FROZEN_OCEAN_ICE_FRACTION = 0.15;
   private static final double WARM_OCEAN_SST_C = 23.0;
   private static final double LUKEWARM_OCEAN_SST_C = 13.0;
   private static final int SUNFLOWER_PLAINS_GRID_BLOCKS = 384;
   private static final int FLOWER_FOREST_GRID_BLOCKS = 320;
   private static final int CHERRY_GROVE_GRID_BLOCKS = 448;
   private static final double SUNFLOWER_PLAINS_NOISE_THRESHOLD = 0.72;
   private static final double FLOWER_FOREST_NOISE_THRESHOLD = 0.72;
   private static final double CHERRY_GROVE_NOISE_THRESHOLD = 0.78;
   private static final double CHERRY_GROVE_MIN_ELEVATION_METERS = 180.0;
   private static final double CHERRY_GROVE_MAX_ELEVATION_METERS = 1800.0;
   private static final double CHERRY_GROVE_MIN_RELIEF_METERS = 35.0;
   private static final double CHERRY_GROVE_MAX_RELIEF_METERS = 520.0;
   private static final double CHERRY_GROVE_RELIEF_SAMPLE_METERS = 640.0;
   private static final long SUNFLOWER_PLAINS_NOISE_SALT = 5012456494189417561L;
   private static final long FLOWER_FOREST_NOISE_SALT = -3588256909144307243L;
   private static final long CHERRY_GROVE_NOISE_SALT = 7999243172794174439L;
   private static final TellusLandCoverSource LAND_COVER_SOURCE = TellusWorldgenSources.landCover();
   private static final TellusElevationSource ELEVATION_SOURCE = TellusWorldgenSources.elevation();
   private static final TellusKoppenSource KOPPEN_SOURCE = TellusWorldgenSources.koppen();
   private static final OisstOceanClimateSource OCEAN_CLIMATE_SOURCE = TellusWorldgenSources.oceanClimate();
   
   private final HolderGetter<Biome> biomeLookup;
   
   private final EarthGeneratorSettings settings;
   private final WorldProjection projection;
   
   private final Set<Holder<Biome>> possibleBiomes;
   
   private final Holder<Biome> plains;

   private final Holder<Biome> sunflowerPlains;

   private final Holder<Biome> flowerForest;

   private final Holder<Biome> cherryGrove;
   
   private final Holder<Biome> ocean;

   private final Holder<Biome> warmOcean;

   private final Holder<Biome> lukewarmOcean;

   private final Holder<Biome> coldOcean;

   private final Holder<Biome> frozenOcean;

   private final Holder<Biome> deepOcean;

   private final Holder<Biome> deepLukewarmOcean;

   private final Holder<Biome> deepColdOcean;

   private final Holder<Biome> deepFrozenOcean;
   
   private final Holder<Biome> river;
   
   private final Holder<Biome> frozenPeaks;
   
   private final Holder<Biome> mangrove;
   
   private final Holder<Biome> lushCaves;
   
   private final Holder<Biome> dripstoneCaves;
   
   private final Holder<Biome> deepDark;
   
   private final WaterSurfaceResolver waterResolver;
   private final RandomBiomeMixer randomBiomeMixer;
   private final ThreadLocal<Integer> structureBiomeQueryDepth = new ThreadLocal<>();
   private final ThreadLocal<EarthBiomeSource.RegionalReliefCache> badlandsReliefCache = ThreadLocal.withInitial(
      EarthBiomeSource.RegionalReliefCache::new
   );
   private final int structureBiomeProbeCeilingY;
   private volatile boolean fastSpawnMode = true;

   public EarthBiomeSource(HolderGetter<Biome> biomeLookup, EarthGeneratorSettings settings) {
      this.biomeLookup = Objects.requireNonNull(biomeLookup, "biomeLookup");
      this.settings = Objects.requireNonNull(settings, "settings");
      this.projection = settings.projection();
      this.structureBiomeProbeCeilingY = EarthGeneratorSettings.resolveHeightLimits(settings).minY()
         + STRUCTURE_BIOME_PROBE_CEILING_ABOVE_MIN_Y;
      this.plains = this.biomeLookup.getOrThrow(Biomes.PLAINS);
      this.sunflowerPlains = this.resolveBiome(Biomes.SUNFLOWER_PLAINS, this.plains);
      this.flowerForest = this.resolveBiome(Biomes.FLOWER_FOREST, this.plains);
      this.cherryGrove = this.resolveBiome(Biomes.CHERRY_GROVE, this.flowerForest);
      this.ocean = this.resolveBiome(Biomes.OCEAN, this.plains);
      this.warmOcean = this.resolveBiome(Biomes.WARM_OCEAN, this.ocean);
      this.lukewarmOcean = this.resolveBiome(Biomes.LUKEWARM_OCEAN, this.ocean);
      this.coldOcean = this.resolveBiome(Biomes.COLD_OCEAN, this.ocean);
      this.frozenOcean = this.resolveBiome(Biomes.FROZEN_OCEAN, this.ocean);
      this.deepOcean = this.resolveBiome(Biomes.DEEP_OCEAN, this.ocean);
      this.deepLukewarmOcean = this.resolveBiome(Biomes.DEEP_LUKEWARM_OCEAN, this.deepOcean);
      this.deepColdOcean = this.resolveBiome(Biomes.DEEP_COLD_OCEAN, this.deepOcean);
      this.deepFrozenOcean = this.resolveBiome(Biomes.DEEP_FROZEN_OCEAN, this.deepOcean);
      this.river = this.resolveBiome(Biomes.RIVER, this.plains);
      this.frozenPeaks = this.resolveBiome(Biomes.FROZEN_PEAKS, this.plains);
      this.mangrove = this.resolveBiome(Biomes.MANGROVE_SWAMP, this.plains);
      this.lushCaves = this.resolveOptionalBiome(Biomes.LUSH_CAVES);
      this.dripstoneCaves = this.resolveOptionalBiome(Biomes.DRIPSTONE_CAVES);
      this.deepDark = this.resolveOptionalBiome(Biomes.DEEP_DARK);
      this.waterResolver = TellusWorldgenSources.waterResolver(this.settings);
      this.randomBiomeMixer = new RandomBiomeMixer(this.biomeLookup, this.settings);
      this.possibleBiomes = this.buildPossibleBiomes();
   }

   public EarthGeneratorSettings settings() {
      return this.settings;
   }

   void setFastSpawnMode(boolean enabled) {
      this.fastSpawnMode = enabled;
   }

   void beginStructureBiomeQueries() {
      Integer depth = this.structureBiomeQueryDepth.get();
      this.structureBiomeQueryDepth.set(depth == null ? 1 : depth + 1);
   }

   void endStructureBiomeQueries() {
      Integer currentDepth = this.structureBiomeQueryDepth.get();
      if (currentDepth == null || currentDepth <= 1) {
         this.structureBiomeQueryDepth.remove();
      } else {
         this.structureBiomeQueryDepth.set(currentDepth - 1);
      }
   }

   
   protected Stream<Holder<Biome>> collectPossibleBiomes() {
      return Objects.requireNonNull(this.possibleBiomes.stream(), "possibleBiomes.stream()");
   }

   
   protected MapCodec<? extends BiomeSource> codec() {
      return Objects.requireNonNull(CODEC, "CODEC");
   }

   
   public Holder<Biome> getNoiseBiome(int x, int y, int z,  Sampler sampler) {
      int blockX = QuartPos.toBlock(x);
      int blockY = QuartPos.toBlock(y);
      int blockZ = QuartPos.toBlock(z);
      return this.resolveBiomeAtBlock(blockX, blockY, blockZ);
   }

   /**
    * Creates a resolver confined to one vanilla chunk-biome task. Vanilla
    * revisits each horizontal quart column for every vertical quart layer, so
    * cache the expensive land-cover/water column while still resolving the
    * final biome independently for every Y coordinate.
    */
   BiomeResolver createChunkBiomeResolver() {
      QuartBiomeColumnCache<EarthBiomeSource.ResolvedBiomeColumn> columns = new QuartBiomeColumnCache<>(
         (quartX, quartZ) -> this.resolveBiomeColumn(QuartPos.toBlock(quartX), QuartPos.toBlock(quartZ), false)
      );
      return (quartX, quartY, quartZ, sampler) -> {
         int blockX = QuartPos.toBlock(quartX);
         int blockY = QuartPos.toBlock(quartY);
         int blockZ = QuartPos.toBlock(quartZ);
         return this.resolveBiomeForColumn(columns.resolve(quartX, quartZ), blockX, blockY, blockZ);
      };
   }

   /**
    * Vanilla samples biome locate queries at absolute Y levels 64 blocks
    * apart. Tellus terrain and cave biomes are surface-relative, so use the
    * exact local terrain column and inspect every eligible biome quart instead.
    */
   @Override
   public Pair<BlockPos, Holder<Biome>> findClosestBiome3d(
      BlockPos origin,
      int radius,
      int horizontalInterval,
      int verticalInterval,
      Predicate<Holder<Biome>> predicate,
      Sampler sampler,
      LevelReader level
   ) {
      Objects.requireNonNull(origin, "origin");
      Objects.requireNonNull(predicate, "predicate");
      if (this.possibleBiomes.stream().noneMatch(predicate)) {
         return null;
      }

      int horizontalStep = Math.max(1, horizontalInterval);
      int horizontalRadius = Math.floorDiv(Math.max(0, radius), horizontalStep);
      for (BlockPos.MutableBlockPos offset : BlockPos.spiralAround(BlockPos.ZERO, horizontalRadius, Direction.EAST, Direction.SOUTH)) {
         int blockX = quartBlock(origin.getX() + offset.getX() * horizontalStep);
         int blockZ = quartBlock(origin.getZ() + offset.getZ() * horizontalStep);
         EarthBiomeSource.ResolvedBiomeColumn resolved = this.resolveBiomeColumn(blockX, blockZ, true);
         int surfaceY = resolved.waterColumn().terrainSurface();
         Holder<Biome> surfaceBiome = resolved.surfaceBiome();
         Pair<BlockPos, Holder<Biome>> closestInColumn = predicate.test(surfaceBiome)
            ? Pair.of(new BlockPos(blockX, surfaceY, blockZ), surfaceBiome)
            : null;
         long closestVerticalDistance = closestInColumn == null ? Long.MAX_VALUE : Math.abs((long)surfaceY - origin.getY());

         if (this.settings.caveGeneration()) {
            for (int blockY : TellusBiomeLocatePolicy.caveBiomeQuartYs(surfaceY, this.settings.undergroundDepth(), origin.getY())) {
               Holder<Biome> biome = this.resolveBiomeForColumn(resolved, blockX, blockY, blockZ);
               if (!predicate.test(biome)) {
                  continue;
               }

               long verticalDistance = Math.abs((long)blockY - origin.getY());
               if (verticalDistance < closestVerticalDistance) {
                  closestInColumn = Pair.of(new BlockPos(blockX, blockY, blockZ), biome);
                  closestVerticalDistance = verticalDistance;
               }
            }
         }

         if (closestInColumn != null) {
            return closestInColumn;
         }
      }

      return null;
   }

   
   public Holder<Biome> getBiomeAtBlock(int blockX, int blockZ) {
      return this.resolveSurfaceBiomeAtBlock(blockX, blockZ);
   }

   public Holder<Biome> getBiomeAtBlock(
      int blockX, int blockZ, int rawCoverClass, int visualCoverClass, WaterSurfaceResolver.WaterColumnData column
   ) {
      return this.getBiomeAtBlock(blockX, blockZ, rawCoverClass, visualCoverClass, column, null);
   }

   public Holder<Biome> getBiomeAtBlock(
      int blockX, int blockZ, int rawCoverClass, int visualCoverClass, boolean hasWater, boolean isOcean
   ) {
      return this.resolveSurfaceBiomeAtBlock(blockX, blockZ, rawCoverClass, visualCoverClass, hasWater, isOcean, null);
   }

   public Holder<Biome> getLodBiomeAtBlock(
      int blockX, int blockZ, int rawCoverClass, int visualCoverClass, boolean hasWater, boolean isOcean
   ) {
      return this.resolveSurfaceBiomeAtBlock(blockX, blockZ, rawCoverClass, visualCoverClass, hasWater, isOcean, null);
   }

   public Holder<Biome> getLodBiomeAtBlock(
      int blockX, int blockZ, int rawCoverClass, int visualCoverClass, boolean hasWater, boolean isOcean, double previewResolutionMeters
   ) {
      return this.resolveSurfaceBiomeAtBlock(
         blockX, blockZ, rawCoverClass, visualCoverClass, hasWater, isOcean, null, previewResolutionMeters
      );
   }

   Holder<Biome> getBiomeAtBlock(
      int blockX, int blockZ, int rawCoverClass, int visualCoverClass, WaterSurfaceResolver.WaterColumnData column, String koppenCode
   ) {
      return this.resolveSurfaceBiomeAtBlock(blockX, blockZ, rawCoverClass, visualCoverClass, column, koppenCode);
   }

   
   private Holder<Biome> resolveSurfaceBiomeAtBlock(int blockX, int blockZ) {
      if (this.fastSpawnMode) {
         return this.resolveFastSpawnSurfaceBiome(blockX, blockZ);
      } else {
         int rawCoverClass = LAND_COVER_SOURCE.sampleCoverClass(blockX, blockZ, this.projection);
         int visualCoverClass = this.sampleVisualCoverClass(blockX, blockZ, rawCoverClass);
         return this.resolveSurfaceBiomeAtBlock(blockX, blockZ, rawCoverClass, visualCoverClass, null, null);
      }
   }

   
   private Holder<Biome> resolveBiomeAtBlock(int blockX, int blockY, int blockZ) {
      Integer structureQueryDepth = this.structureBiomeQueryDepth.get();
      boolean structureBiomeQuery = structureQueryDepth != null && structureQueryDepth > 0;
      if (structureBiomeQuery && blockY <= this.structureBiomeProbeCeilingY) {
         return this.resolveStructureBiomeProbe(blockX, blockZ);
      } else if (this.fastSpawnMode) {
         return this.resolveFastSpawnSurfaceBiome(blockX, blockZ);
      } else {
         return this.resolveBiomeForColumn(this.resolveBiomeColumn(blockX, blockZ, false), blockX, blockY, blockZ);
      }
   }

   private EarthBiomeSource.ResolvedBiomeColumn resolveBiomeColumn(int blockX, int blockZ, boolean exact) {
      int rawCoverClass = LAND_COVER_SOURCE.sampleCoverClass(blockX, blockZ, this.projection);
      int visualCoverClass = this.sampleVisualCoverClass(blockX, blockZ, rawCoverClass);
      WaterSurfaceResolver.WaterColumnData column = exact
         ? this.waterResolver.resolveColumnData(blockX, blockZ, rawCoverClass)
         : this.settings.enableWater()
            ? this.waterResolver.resolveFastColumnData(blockX, blockZ, rawCoverClass)
            : this.waterResolver.resolveColumnData(blockX, blockZ, rawCoverClass);
      Holder<Biome> surfaceBiome = this.resolveSurfaceBiomeAtBlock(blockX, blockZ, rawCoverClass, visualCoverClass, column, null);
      return new EarthBiomeSource.ResolvedBiomeColumn(surfaceBiome, column);
   }

   private Holder<Biome> resolveBiomeForColumn(
      EarthBiomeSource.ResolvedBiomeColumn resolved, int blockX, int blockY, int blockZ
   ) {
      Holder<Biome> surfaceBiome = resolved.surfaceBiome();
      if (!this.settings.caveGeneration()) {
         return surfaceBiome;
      }

      int depth = resolved.waterColumn().terrainSurface() - blockY;
      return TellusCaveBiomeDepthPolicy.isCaveBiomeDepth(depth, this.settings.undergroundDepth())
         ? this.resolveCaveBiome(surfaceBiome, blockX, blockY, blockZ, depth)
         : surfaceBiome;
   }

   private static int quartBlock(int blockCoordinate) {
      return QuartPos.toBlock(QuartPos.fromBlock(blockCoordinate));
   }

   private Holder<Biome> resolveStructureBiomeProbe(int blockX, int blockZ) {
      int probeDepth = TellusCaveBiomeDepthPolicy.structureProbeDepth(this.settings.undergroundDepth());
      return probeDepth != TellusCaveBiomeDepthPolicy.NO_STRUCTURE_PROBE_DEPTH && this.isDeepDarkAtDepth(blockX, blockZ, probeDepth)
         ? this.deepDark
         : this.plains;
   }

   
   private Holder<Biome> resolveFastSpawnSurfaceBiome(int blockX, int blockZ) {
      int rawCoverClass = LAND_COVER_SOURCE.sampleCoverClass(blockX, blockZ, this.projection);
      int visualCoverClass = this.sampleVisualCoverClass(blockX, blockZ, rawCoverClass);
      if (rawCoverClass == ESA_MANGROVES) {
         return this.mangrove;
      } else if (this.settings.enableWater()) {
         WaterSurfaceResolver.WaterInfo waterInfo = this.waterResolver.resolveFastWaterInfo(blockX, blockZ, rawCoverClass);
         return waterInfo.isWater()
            ? (waterInfo.isOcean() ? this.ocean : this.river)
            : (visualCoverClass == ESA_SNOW_ICE ? this.frozenPeaks : this.plains);
      } else if (rawCoverClass == ESA_WATER) {
         return this.ocean;
      } else {
         return visualCoverClass == ESA_SNOW_ICE ? this.frozenPeaks : (rawCoverClass == ESA_NO_DATA ? this.ocean : this.plains);
      }
   }

   
   private Holder<Biome> resolveSurfaceBiomeAtBlock(
      int blockX, int blockZ, int rawCoverClass, int visualCoverClass,  WaterSurfaceResolver.WaterColumnData column, String precomputedKoppen
   ) {
      if (rawCoverClass == ESA_MANGROVES) {
         return this.mangrove;
      }
      if (this.settings.enableWater()) {
         WaterSurfaceResolver.WaterColumnData waterColumn = column != null
            ? column
            : this.waterResolver.resolveFastColumnData(blockX, blockZ, rawCoverClass);
         if (waterColumn.hasWater()) {
            return waterColumn.isOcean() ? this.resolveOceanBiome(blockX, blockZ, waterColumn) : this.applyRandomRiverBiome(blockX, blockZ);
         }
         visualCoverClass = this.resolveDryOsmVisualCoverClass(blockX, blockZ, rawCoverClass, visualCoverClass);
      } else if (rawCoverClass == ESA_NO_DATA || rawCoverClass == ESA_WATER) {
         WaterSurfaceResolver.WaterColumnData waterColumn = column != null ? column : this.waterResolver.resolveColumnData(blockX, blockZ, rawCoverClass);
         if (waterColumn.hasWater()) {
            return waterColumn.isOcean() ? this.resolveOceanBiome(blockX, blockZ, waterColumn) : this.applyRandomRiverBiome(blockX, blockZ);
         }
      }
      return this.resolveSurfaceBiomeAfterWater(blockX, blockZ, visualCoverClass, precomputedKoppen);
   }

   private Holder<Biome> resolveSurfaceBiomeAtBlock(
      int blockX, int blockZ, int rawCoverClass, int visualCoverClass, boolean hasWater, boolean isOcean, String precomputedKoppen
   ) {
      return this.resolveSurfaceBiomeAtBlock(
         blockX, blockZ, rawCoverClass, visualCoverClass, hasWater, isOcean, precomputedKoppen, Double.NaN
      );
   }

   private Holder<Biome> resolveSurfaceBiomeAtBlock(
      int blockX,
      int blockZ,
      int rawCoverClass,
      int visualCoverClass,
      boolean hasWater,
      boolean isOcean,
      String precomputedKoppen,
      double previewResolutionMeters
   ) {
      if (rawCoverClass == ESA_MANGROVES) {
         return this.mangrove;
      }
      if ((this.settings.enableWater() || rawCoverClass == ESA_NO_DATA || rawCoverClass == ESA_WATER) && hasWater) {
         return isOcean ? this.resolveOceanBiome(blockX, blockZ, null, previewResolutionMeters) : this.applyRandomRiverBiome(blockX, blockZ);
      }
      if (this.settings.enableWater() && !hasWater) {
         visualCoverClass = this.resolveDryOsmVisualCoverClass(blockX, blockZ, rawCoverClass, visualCoverClass);
      }
      return this.resolveSurfaceBiomeAfterWater(blockX, blockZ, visualCoverClass, precomputedKoppen);
   }

   private int resolveDryOsmVisualCoverClass(int blockX, int blockZ, int rawCoverClass, int visualCoverClass) {
      if (rawCoverClass != ESA_WATER) {
         return visualCoverClass;
      }

      int nearest = LAND_COVER_SOURCE.sampleNearestLandCoverClassLocalOnly(
         blockX, blockZ, this.projection, ESA_BARE
      );
      return nearest == Integer.MIN_VALUE ? ESA_BARE : nearest;
   }

   private Holder<Biome> resolveOceanBiome(int blockX, int blockZ, WaterSurfaceResolver.WaterColumnData column) {
      return this.resolveOceanBiome(blockX, blockZ, column, Double.NaN);
   }

   private Holder<Biome> resolveOceanBiome(
      int blockX, int blockZ, WaterSurfaceResolver.WaterColumnData column, double previewResolutionMeters
   ) {
      OisstOceanClimateSource.Sample climate = OCEAN_CLIMATE_SOURCE.sample(blockX, blockZ, this.projection);
      boolean deep = this.isDeepOcean(blockX, blockZ, column, previewResolutionMeters);
      Holder<Biome> biome;
      if (climate.maxIceFraction() >= FROZEN_OCEAN_ICE_FRACTION || climate.meanSstC() <= FROZEN_OCEAN_SST_C) {
         biome = deep ? this.deepFrozenOcean : this.frozenOcean;
      } else if (climate.meanSstC() >= WARM_OCEAN_SST_C) {
         biome = deep ? this.deepLukewarmOcean : this.warmOcean;
      } else if (climate.meanSstC() >= LUKEWARM_OCEAN_SST_C) {
         biome = deep ? this.deepLukewarmOcean : this.lukewarmOcean;
      } else {
         biome = deep ? this.deepColdOcean : this.coldOcean;
      }

      return this.applyRandomOceanBiome(biome, blockX, blockZ, deep);
   }

   private boolean isDeepOcean(
      int blockX, int blockZ, WaterSurfaceResolver.WaterColumnData column, double previewResolutionMeters
   ) {
      double depthMeters = this.sampleOceanDepthMeters(blockX, blockZ, column, previewResolutionMeters);
      return Double.isFinite(depthMeters) && depthMeters >= DEEP_OCEAN_DEPTH_METERS;
   }

   private double sampleOceanDepthMeters(
      int blockX, int blockZ, WaterSurfaceResolver.WaterColumnData column, double previewResolutionMeters
   ) {
      double oceanElevation = Double.isFinite(previewResolutionMeters) && previewResolutionMeters > 0.0
         ? ELEVATION_SOURCE.samplePreviewOceanElevationMeters(
            blockX, blockZ, this.projection, this.settings.demSelection(), previewResolutionMeters
         )
         : ELEVATION_SOURCE.sampleOceanElevationMeters(
            blockX, blockZ, this.projection, this.settings.demSelection()
         );
      if (Double.isFinite(oceanElevation) && oceanElevation < 0.0) {
         return -oceanElevation;
      } else if (column != null && column.hasWater()) {
         return Math.max(0.0, column.waterSurface() - column.terrainSurface()) * Math.max(1.0, this.settings.worldScale());
      } else {
         return Double.NaN;
      }
   }

   private Holder<Biome> resolveSurfaceBiomeAfterWater(int blockX, int blockZ, int visualCoverClass, String precomputedKoppen) {
      if (visualCoverClass == ESA_SNOW_ICE) {
         return this.applyRandomLandBiome(this.frozenPeaks, blockX, blockZ, null);
      }

      String koppen = precomputedKoppen;
      if (koppen == null) {
         koppen = KOPPEN_SOURCE.sampleDitheredCode(blockX, blockZ, this.projection);
         if (koppen == null) {
            koppen = KOPPEN_SOURCE.findNearestCode(blockX, blockZ, this.projection);
         }
      }

      if (BadlandsTerrainPolicy.isDryCanyonCover(visualCoverClass)) {
         String coherentKoppen = KOPPEN_SOURCE.sampleSmoothedCode(blockX, blockZ, this.projection);
         if (BadlandsTerrainPolicy.shouldUseCoherentAridClimate(visualCoverClass, coherentKoppen)) {
            koppen = coherentKoppen;
         }
      }

      ResourceKey<Biome> biomeKey = BiomeClassificationProviders.findBiomeKey(
         visualCoverClass,
         koppen,
         blockX,
         blockZ,
         this.projection
      );
      if (biomeKey == null) {
         biomeKey = BiomeClassification.findBiomeKey(visualCoverClass, koppen);
      }
      if (biomeKey == null) {
         biomeKey = BiomeClassification.findFallbackKey(visualCoverClass);
      }

      if (!Biomes.BADLANDS.equals(biomeKey)
         && BadlandsTerrainPolicy.isDryCanyonCover(visualCoverClass)
         && BadlandsTerrainPolicy.isAridClimate(koppen)
         && BadlandsTerrainPolicy.shouldPromoteToBadlands(
            visualCoverClass, koppen, this.sampleRegionalReliefMeters(blockX, blockZ)
         )) {
         biomeKey = Biomes.BADLANDS;
      }

      Holder<Biome> biome = biomeKey == null ? this.plains : this.resolveBiome(biomeKey, this.plains);
      biome = this.applyRareBiomeVariants(biome, blockX, blockZ, visualCoverClass, koppen);
      return this.applyRandomLandBiome(biome, blockX, blockZ, koppen);
   }

   private Holder<Biome> applyRandomLandBiome(Holder<Biome> base, int blockX, int blockZ, String koppen) {
      return this.settings.randomBiomes() ? this.randomBiomeMixer.mixLand(base, blockX, blockZ, koppen) : base;
   }

   private Holder<Biome> applyRandomOceanBiome(Holder<Biome> base, int blockX, int blockZ, boolean deep) {
      return this.settings.randomBiomes() ? this.randomBiomeMixer.mixOcean(base, blockX, blockZ, deep) : base;
   }

   private Holder<Biome> applyRandomRiverBiome(int blockX, int blockZ) {
      return this.settings.randomBiomes() ? this.randomBiomeMixer.mixRiver(this.river, blockX, blockZ) : this.river;
   }

   private Holder<Biome> applyRareBiomeVariants(Holder<Biome> biome, int blockX, int blockZ, int visualCoverClass, String koppen) {
      if (koppen == null) {
         return biome;
      }

      String normalizedKoppen = koppen.toUpperCase(Locale.ROOT);
      if (visualCoverClass == ESA_TREE_COVER && isTemperateForestBiome(biome)) {
         if (this.isCherryGroveCandidate(blockX, blockZ, normalizedKoppen)) {
            return this.cherryGrove;
         } else if (isFlowerForestClimate(normalizedKoppen)
            && sampleValueNoise(blockX, blockZ, FLOWER_FOREST_GRID_BLOCKS, FLOWER_FOREST_NOISE_SALT) >= FLOWER_FOREST_NOISE_THRESHOLD) {
            return this.flowerForest;
         }
      } else if ((visualCoverClass == ESA_GRASSLAND || visualCoverClass == ESA_CROPLAND)
         && biome.is(Biomes.PLAINS)
         && isSunflowerPlainsClimate(normalizedKoppen)
         && sampleValueNoise(blockX, blockZ, SUNFLOWER_PLAINS_GRID_BLOCKS, SUNFLOWER_PLAINS_NOISE_SALT) >= SUNFLOWER_PLAINS_NOISE_THRESHOLD) {
         return this.sunflowerPlains;
      }

      return biome;
   }

   private boolean isCherryGroveCandidate(int blockX, int blockZ, String koppen) {
      return this.settings.randomBiomes()
         && this.settings.randomBiomeIds().contains("cherry_grove")
         && isCherryGroveClimate(koppen)
         && sampleValueNoise(blockX, blockZ, CHERRY_GROVE_GRID_BLOCKS, CHERRY_GROVE_NOISE_SALT) >= CHERRY_GROVE_NOISE_THRESHOLD
         && this.isCherryGroveTerrain(blockX, blockZ);
   }

   private boolean isCherryGroveTerrain(int blockX, int blockZ) {
      double worldScale = this.settings.worldScale();
      if (worldScale <= 0.0) {
         return false;
      }

      double center = ELEVATION_SOURCE.sampleElevationMeters(blockX, blockZ, this.projection, false, this.settings.demSelection());
      if (!Double.isFinite(center) || center < CHERRY_GROVE_MIN_ELEVATION_METERS || center > CHERRY_GROVE_MAX_ELEVATION_METERS) {
         return false;
      }

      int sampleOffset = Math.max(4, Mth.ceil(CHERRY_GROVE_RELIEF_SAMPLE_METERS / Math.max(1.0, worldScale)));
      double east = ELEVATION_SOURCE.sampleElevationMeters(blockX + sampleOffset, blockZ, this.projection, false, this.settings.demSelection());
      double west = ELEVATION_SOURCE.sampleElevationMeters(blockX - sampleOffset, blockZ, this.projection, false, this.settings.demSelection());
      double south = ELEVATION_SOURCE.sampleElevationMeters(blockX, blockZ + sampleOffset, this.projection, false, this.settings.demSelection());
      double north = ELEVATION_SOURCE.sampleElevationMeters(blockX, blockZ - sampleOffset, this.projection, false, this.settings.demSelection());
      double min = center;
      double max = center;
      int samples = 1;

      if (Double.isFinite(east)) {
         min = Math.min(min, east);
         max = Math.max(max, east);
         samples++;
      }

      if (Double.isFinite(west)) {
         min = Math.min(min, west);
         max = Math.max(max, west);
         samples++;
      }

      if (Double.isFinite(south)) {
         min = Math.min(min, south);
         max = Math.max(max, south);
         samples++;
      }

      if (Double.isFinite(north)) {
         min = Math.min(min, north);
         max = Math.max(max, north);
         samples++;
      }

      double relief = max - min;
      return samples >= 3 && relief >= CHERRY_GROVE_MIN_RELIEF_METERS && relief <= CHERRY_GROVE_MAX_RELIEF_METERS;
   }

   private double sampleRegionalReliefMeters(int blockX, int blockZ) {
      double worldScale = this.settings.worldScale();
      if (!(worldScale > 0.0)) {
         return Double.NaN;
      }

      int cellSize = BadlandsTerrainPolicy.regionalSampleCellBlocks(worldScale);
      int cellX = Math.floorDiv(blockX, cellSize);
      int cellZ = Math.floorDiv(blockZ, cellSize);
      EarthBiomeSource.RegionalReliefCache cache = this.badlandsReliefCache.get();
      if (cache.cellX == cellX && cache.cellZ == cellZ) {
         return cache.reliefMeters;
      }

      int sampleX = cellCenter(cellX, cellSize);
      int sampleZ = cellCenter(cellZ, cellSize);
      int offset = Math.max(4, Mth.ceil(BadlandsTerrainPolicy.CANYON_RELIEF_SAMPLE_METERS / worldScale));
      double[] elevations = new double[]{
         ELEVATION_SOURCE.sampleElevationMeters(sampleX, sampleZ, this.projection, false, this.settings.demSelection()),
         ELEVATION_SOURCE.sampleElevationMeters(offsetCoordinate(sampleX, offset), sampleZ, this.projection, false, this.settings.demSelection()),
         ELEVATION_SOURCE.sampleElevationMeters(offsetCoordinate(sampleX, -offset), sampleZ, this.projection, false, this.settings.demSelection()),
         ELEVATION_SOURCE.sampleElevationMeters(sampleX, offsetCoordinate(sampleZ, offset), this.projection, false, this.settings.demSelection()),
         ELEVATION_SOURCE.sampleElevationMeters(sampleX, offsetCoordinate(sampleZ, -offset), this.projection, false, this.settings.demSelection())
      };
      double min = Double.POSITIVE_INFINITY;
      double max = Double.NEGATIVE_INFINITY;
      int samples = 0;
      for (double elevation : elevations) {
         if (Double.isFinite(elevation)) {
            min = Math.min(min, elevation);
            max = Math.max(max, elevation);
            samples++;
         }
      }

      double relief = samples >= 3 ? max - min : Double.NaN;
      cache.cellX = cellX;
      cache.cellZ = cellZ;
      cache.reliefMeters = relief;
      return relief;
   }

   private static int cellCenter(int cell, int cellSize) {
      long center = (long)cell * cellSize + cellSize / 2L;
      return (int)Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, center));
   }

   private static int offsetCoordinate(int coordinate, int offset) {
      long result = (long)coordinate + offset;
      return (int)Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, result));
   }

   private int sampleVisualCoverClass(int blockX, int blockZ, int rawCoverClass) {
      double worldScale = this.settings.worldScale();
      return worldScale > 0.0 && worldScale < 10.0
         ? LAND_COVER_SOURCE.sampleVisualCoverClass(blockX, blockZ, this.projection)
         : rawCoverClass;
   }

   
   private Set<Holder<Biome>> buildPossibleBiomes() {
      Set<Holder<Biome>> holders = new HashSet<>();

      for (ResourceKey<Biome> key : BiomeClassification.allBiomeKeys()) {
         holders.add(this.resolveBiome(key, this.plains));
      }
      for (ResourceKey<Biome> key : BiomeClassificationProviders.allBiomeKeys()) {
         holders.add(this.resolveBiome(key, this.plains));
      }

      holders.add(this.plains);
      holders.add(this.sunflowerPlains);
      holders.add(this.flowerForest);
      holders.add(this.ocean);
      holders.add(this.warmOcean);
      holders.add(this.lukewarmOcean);
      holders.add(this.coldOcean);
      holders.add(this.frozenOcean);
      holders.add(this.deepOcean);
      holders.add(this.deepLukewarmOcean);
      holders.add(this.deepColdOcean);
      holders.add(this.deepFrozenOcean);
      holders.add(this.river);
      holders.add(this.frozenPeaks);
      holders.add(this.mangrove);
      if (this.settings.randomBiomes()) {
         holders.add(this.cherryGrove);
         holders.addAll(this.randomBiomeMixer.possibleBiomes());
      }
      if (this.settings.caveGeneration()) {
         addIfPresent(holders, this.lushCaves);
         addIfPresent(holders, this.dripstoneCaves);
         if (this.settings.deepDark()) {
            addIfPresent(holders, this.deepDark);
         }
      }

      return holders;
   }

   
   private Holder<Biome> resolveCaveBiome( Holder<Biome> surfaceBiome, int blockX, int blockY, int blockZ, int depth) {
      if (this.settings.randomBiomes()) {
         Holder<Biome> selected = this.randomBiomeMixer.mixCave(surfaceBiome, blockX, blockY, blockZ);
         if (selected != surfaceBiome) {
            return selected;
         }
      }

      double depthFactor = TellusCaveBiomeDepthPolicy.normalizedDepthFactor(depth, this.settings.undergroundDepth());
      double noise = sampleCaveNoise(blockX, blockY, blockZ, CAVE_BIOME_GRID, CAVE_BIOME_Y_GRID);
      Holder<Biome> lushCavesBiome = this.lushCaves;
      Holder<Biome> dripstoneCavesBiome = this.dripstoneCaves;
      if (this.isDeepDarkAtDepth(blockX, blockZ, depth)) {
         return this.deepDark;
      }

      double lushChance = (isLushSurface(surfaceBiome) ? 0.45 : 0.25) * (1.0 - depthFactor * 0.35);
      double dripChance = (isDrySurface(surfaceBiome) ? 0.45 : 0.25) * (0.7 + depthFactor * 0.5);
      if (depth < LUSH_MIN_DEPTH) {
         lushChance = 0.0;
      }

      if (depth < DRIPSTONE_MIN_DEPTH) {
         dripChance = 0.0;
      }

      double total = lushChance + dripChance;
      if (!(total <= 0.0) && !(noise > MAX_CAVE_BIOME_CHANCE)) {
         double pick = noise * total / MAX_CAVE_BIOME_CHANCE;
         if (lushCavesBiome != null && pick < lushChance) {
            return lushCavesBiome;
         } else {
            return dripstoneCavesBiome != null && pick < lushChance + dripChance ? dripstoneCavesBiome : surfaceBiome;
         }
      } else {
         return surfaceBiome;
      }
   }

   private boolean isDeepDarkAtDepth(int blockX, int blockZ, int depth) {
      if (!this.settings.deepDark()
         || this.deepDark == null
         || !TellusCaveBiomeDepthPolicy.isDeepDarkDepth(depth, this.settings.undergroundDepth())) {
         return false;
      }

      double depthFactor = TellusCaveBiomeDepthPolicy.normalizedDepthFactor(depth, this.settings.undergroundDepth());
      double deepNoise = sampleCaveNoise(blockX, -depth, blockZ, DEEP_DARK_GRID, DEEP_DARK_Y_GRID);
      return deepNoise < 0.28 + depthFactor * 0.22;
   }

   private static boolean isLushSurface(Holder<Biome> surfaceBiome) {
      return surfaceBiome.is(Biomes.JUNGLE)
         || surfaceBiome.is(Biomes.SPARSE_JUNGLE)
         || surfaceBiome.is(Biomes.BAMBOO_JUNGLE)
         || surfaceBiome.is(Biomes.SWAMP)
         || surfaceBiome.is(Biomes.MANGROVE_SWAMP)
         || surfaceBiome.is(Biomes.DARK_FOREST)
         || surfaceBiome.is(Biomes.FOREST)
         || surfaceBiome.is(Biomes.FLOWER_FOREST)
         || surfaceBiome.is(Biomes.CHERRY_GROVE)
         || surfaceBiome.is(Biomes.BIRCH_FOREST)
         || surfaceBiome.is(Biomes.OLD_GROWTH_BIRCH_FOREST)
         || surfaceBiome.is(Biomes.OLD_GROWTH_PINE_TAIGA)
         || surfaceBiome.is(Biomes.OLD_GROWTH_SPRUCE_TAIGA)
         || surfaceBiome.is(Biomes.TAIGA);
   }

   private static boolean isDrySurface(Holder<Biome> surfaceBiome) {
      return surfaceBiome.is(Biomes.DESERT)
         || surfaceBiome.is(Biomes.BADLANDS)
         || surfaceBiome.is(Biomes.WOODED_BADLANDS)
         || surfaceBiome.is(Biomes.ERODED_BADLANDS)
         || surfaceBiome.is(Biomes.SAVANNA)
         || surfaceBiome.is(Biomes.SAVANNA_PLATEAU)
         || surfaceBiome.is(Biomes.WINDSWEPT_SAVANNA);
   }

   private static double sampleCaveNoise(int blockX, int blockY, int blockZ, int gridXZ, int gridY) {
      int x = Math.floorDiv(blockX, gridXZ);
      int y = Math.floorDiv(blockY, gridY);
      int z = Math.floorDiv(blockZ, gridXZ);
      long seed = x * 341873128712L + z * 132897987541L + y * 42317861L;
      return hashToUnit(seed);
   }

   private static boolean isTemperateForestBiome(Holder<Biome> biome) {
      return biome.is(Biomes.FOREST)
         || biome.is(Biomes.BIRCH_FOREST)
         || biome.is(Biomes.OLD_GROWTH_BIRCH_FOREST)
         || biome.is(Biomes.DARK_FOREST)
         || biome.is(Biomes.FLOWER_FOREST)
         || biome.is(Biomes.CHERRY_GROVE);
   }

   private static boolean isSunflowerPlainsClimate(String koppen) {
      return switch (koppen) {
         case "CFA", "CWA", "DFA", "DWA" -> true;
         default -> false;
      };
   }

   private static boolean isFlowerForestClimate(String koppen) {
      return switch (koppen) {
         case "CFA", "CFB", "CWA", "CWB", "DFA", "DFB", "DWA", "DWB" -> true;
         default -> false;
      };
   }

   private static boolean isCherryGroveClimate(String koppen) {
      return switch (koppen) {
         case "CFA", "CFB", "CWA", "CWB", "DFA", "DFB" -> true;
         default -> false;
      };
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
      long seed = gridX * 341873128712L + gridZ * 132897987541L + salt;
      return hashToUnit(seed);
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

   private static void addIfPresent(Set<Holder<Biome>> holders,  Holder<Biome> biome) {
      if (biome != null) {
         holders.add(biome);
      }
   }

   
   private Holder<Biome> resolveBiome( ResourceKey<Biome> key,  Holder<Biome> fallback) {
      if (key == null) {
         return fallback;
      } else {
         Holder<Biome> resolved = this.biomeLookup.get(key).map(holder -> (Holder<Biome>)holder).orElse(fallback);
         return Objects.requireNonNull(resolved, "resolvedBiome");
      }
   }

   
   private Holder<Biome> resolveOptionalBiome( ResourceKey<Biome> key) {
      return key == null ? null : this.biomeLookup.get(key).map(holder -> (Holder<Biome>)holder).orElse(null);
   }

   private record ResolvedBiomeColumn(Holder<Biome> surfaceBiome, WaterSurfaceResolver.WaterColumnData waterColumn) {
   }

   private static final class RegionalReliefCache {
      private int cellX = Integer.MIN_VALUE;
      private int cellZ = Integer.MIN_VALUE;
      private double reliefMeters = Double.NaN;
   }
}
