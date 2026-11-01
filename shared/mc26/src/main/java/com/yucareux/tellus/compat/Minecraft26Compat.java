package com.yucareux.tellus.compat;

import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.HighYPackedCoordinateProfile;
import com.yucareux.tellus.worldgen.RandomBiomeCatalog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.storage.LevelDataAndDimensions;
import net.minecraft.world.level.storage.WorldData;

/** Minecraft APIs shared by the 26.2 and 26.3 overlays. */
public abstract class Minecraft26Compat {
   protected Minecraft26Compat() {
   }

   public static Executor backgroundExecutor() {
      return Util.backgroundExecutor();
   }

   public static List<String> defaultRandomBiomeIds() {
      return RandomBiomeCatalog.minecraft26_2OverworldBiomeIds();
   }

   public static List<String> normalizeRandomBiomeSelection(List<String> biomeIds) {
      return RandomBiomeCatalog.normalizeMinecraft26_2Selection(biomeIds);
   }

   public static DimensionType applyHeightLimits(DimensionType base, EarthGeneratorSettings.HeightLimits limits) {
      return new DimensionType(
         base.hasFixedTime(),
         base.hasSkyLight(),
         base.hasCeiling(),
         base.hasEndFlashes(),
         base.coordinateScale(),
         limits.minY(),
         limits.height(),
         limits.logicalHeight(),
         base.infiniburn(),
         base.ambientLight(),
         base.monsterSettings(),
         base.skybox(),
         base.cardinalLightType(),
         base.attributes(),
         base.timelines(),
         base.defaultClock()
      );
   }

   public static void validatePackedHorizontalLength(List<String> failures) {
      if (BlockPos.PACKED_HORIZONTAL_LENGTH != HighYPackedCoordinateProfile.HORIZONTAL_BITS) {
         failures.add(
            "BlockPos.PACKED_HORIZONTAL_LENGTH="
               + BlockPos.PACKED_HORIZONTAL_LENGTH
               + ", expected compatibility value "
               + HighYPackedCoordinateProfile.HORIZONTAL_BITS
         );
      }
   }

   public static int chunkX(ChunkPos pos) {
      return pos.x();
   }

   public static int chunkZ(ChunkPos pos) {
      return pos.z();
   }

   public static long packChunkPos(int chunkX, int chunkZ) {
      return ChunkPos.pack(chunkX, chunkZ);
   }

   public static ChunkPos chunkPosContaining(BlockPos position) {
      return ChunkPos.containing(position);
   }

   public static void markPosForPostProcessing(ChunkAccess chunk, BlockPos position) {
      chunk.markPosForPostProcessing(position);
   }

   public static Axolotl createAxolotl(ServerLevel level, BlockPos position) {
      return (Axolotl)EntityTypes.AXOLOTL.create(
         level, entity -> {}, position, EntitySpawnReason.CHUNK_GENERATION, false, false
      );
   }

   public static ServerLevel serverLevel(ServerPlayer player) {
      return player.level();
   }

   public static int maxBuildHeight(WorldGenLevel level) {
      return level.getMaxY();
   }

   public static boolean isInsideBuildHeight(WorldGenLevel level, BlockPos position) {
      return level.isInsideBuildHeight(position);
   }

   public static boolean isPaleGarden(Holder<Biome> biome) {
      return biome.is(Biomes.PALE_GARDEN);
   }

   public static boolean isPaleGarden(ResourceKey<Biome> biomeKey) {
      return Biomes.PALE_GARDEN.equals(biomeKey);
   }

   public static Block paleOakLogOr(Block fallback) {
      return Blocks.PALE_OAK_LOG;
   }

   public static Block paleOakLeavesOr(Block fallback) {
      return Blocks.PALE_OAK_LEAVES;
   }

   public static int minY(LevelHeightAccessor level) {
      return level.getMinY();
   }

   public static int maxY(LevelHeightAccessor level) {
      return level.getMaxY();
   }

   public static void setBlockState(ChunkAccess chunk, BlockPos position, BlockState state) {
      chunk.setBlockState(position, state);
   }

   public static <T> Registry<T> registry(RegistryAccess access, ResourceKey<? extends Registry<? extends T>> key) {
      return access.lookupOrThrow(key);
   }

   public static WorldData worldData(WorldStem stem) {
      return stem.worldDataAndGenSettings().data();
   }

   public static WorldStem withRegistries(WorldStem stem, LayeredRegistryAccess<RegistryLayer> registries, UnaryOperator<LevelStem> levelStems) {
      // World gen settings are saved on their own with their own level stems.
      WorldGenSettings genSettings = stem.worldDataAndGenSettings().genSettings();
      Map<ResourceKey<LevelStem>, LevelStem> dimensions = new LinkedHashMap<>();
      genSettings.dimensions().dimensions().forEach((key, levelStem) -> dimensions.put(key, levelStems.apply(levelStem)));
      return new WorldStem(
         stem.resourceManager(),
         stem.dataPackResources(),
         registries,
         new LevelDataAndDimensions.WorldDataAndGenSettings(worldData(stem), new WorldGenSettings(genSettings.options(), new WorldDimensions(dimensions)))
      );
   }

   public static <T> T registryValue(Registry<T> registry, ResourceKey<T> key) {
      return registry.getValueOrThrow(key);
   }

   public static <T> Holder<T> registryHolder(Registry<T> registry, ResourceKey<T> key) {
      return registry.getOrThrow(key);
   }

   public static String keyPath(ResourceKey<?> key) {
      return key.identifier().getPath();
   }

   public static String keyId(ResourceKey<?> key) {
      return key.identifier().toString();
   }

   public static <T> String registryKeyPath(Registry<T> registry, T value) {
      Identifier id = registry.getKey(value);
      return id == null ? null : id.getPath();
   }

   public static ServerBossEvent bossEvent(Component name, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay) {
      return new ServerBossEvent(UUID.randomUUID(), name, color, overlay);
   }

   public static <T> CompletableFuture<T> supplyBackground(String taskName, Supplier<T> task) {
      return CompletableFuture.supplyAsync(task, Util.backgroundExecutor().forName(taskName));
   }

   public static void snapTo(Entity entity, double x, double y, double z, float yRot, float xRot) {
      entity.snapTo(x, y, z, yRot, xRot);
   }

   public static void copyCarvers(BiomeGenerationSettings original, BiomeGenerationSettings.PlainBuilder builder, Predicate<Holder<?>> keep) {
      for (var carver : original.getCarvers()) {
         var safeCarver = Objects.requireNonNull(carver, "carver");
         if (keep.test(safeCarver)) {
            builder.addCarver(safeCarver);
         }
      }
   }
}
