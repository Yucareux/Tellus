package com.yucareux.tellus.compat;

import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.RandomBiomeCatalog;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.storage.WorldData;

/** Narrow compatibility seam for Minecraft APIs that changed after 1.21.1. */
public final class MinecraftVersionCompat {
   private MinecraftVersionCompat() {
   }

   public static Executor backgroundExecutor() {
      return Util.backgroundExecutor();
   }

   public static List<String> defaultRandomBiomeIds() {
      return RandomBiomeCatalog.legacyOverworldBiomeIds();
   }

   public static List<String> normalizeRandomBiomeSelection(List<String> biomeIds) {
      return RandomBiomeCatalog.normalizeLegacySelection(biomeIds);
   }

   public static DimensionType applyHeightLimits(DimensionType base, EarthGeneratorSettings.HeightLimits limits) {
      return new DimensionType(
         base.fixedTime(),
         base.hasSkyLight(),
         base.hasCeiling(),
         base.ultraWarm(),
         base.natural(),
         base.coordinateScale(),
         base.bedWorks(),
         base.respawnAnchorWorks(),
         limits.minY(),
         limits.height(),
         limits.logicalHeight(),
         base.infiniburn(),
         base.effectsLocation(),
         base.ambientLight(),
         base.monsterSettings()
      );
   }

   public static HolderSet<Block> overworldCarverReplaceables(Registry<Block> blockRegistry) {
      return blockRegistry.getOrCreateTag(BlockTags.OVERWORLD_CARVER_REPLACEABLES);
   }

   public static DensityFunctions.BeardifierOrMarker emptyBeardifier() {
      return new Beardifier(
         new ObjectArrayList<Beardifier.Rigid>().iterator(),
         new ObjectArrayList<JigsawJunction>().iterator()
      );
   }

   public static void validatePackedHorizontalLength(List<String> failures) {
      // The horizontal packed-length constant is not exposed by these versions.
   }

   public static int chunkX(ChunkPos pos) {
      return pos.x;
   }

   public static int chunkZ(ChunkPos pos) {
      return pos.z;
   }

   public static long packChunkPos(int chunkX, int chunkZ) {
      return ChunkPos.asLong(chunkX, chunkZ);
   }

   public static ServerLevel serverLevel(ServerPlayer player) {
      return player.serverLevel();
   }

   public static int maxBuildHeight(WorldGenLevel level) {
      return level.getMaxBuildHeight();
   }

   public static boolean isInsideBuildHeight(WorldGenLevel level, BlockPos position) {
      return !level.isOutsideBuildHeight(position);
   }

   public static boolean isPaleGarden(Holder<Biome> biome) {
      return false;
   }

   public static boolean isPaleGarden(ResourceKey<Biome> biomeKey) {
      return false;
   }

   public static Block paleOakLogOr(Block fallback) {
      return fallback;
   }

   public static Block paleOakLeavesOr(Block fallback) {
      return fallback;
   }

   public static void markPosForPostProcessing(ChunkAccess chunk, BlockPos position) {
      chunk.markPosForPostprocessing(position);
   }

   public static Axolotl createAxolotl(ServerLevel level, BlockPos position) {
      return MinecraftRelease.createAxolotl(level, position);
   }

   public static int minY(LevelHeightAccessor level) {
      return level.getMinBuildHeight();
   }

   /** The highest buildable Y, inclusive like the later getMaxY. */
   public static int maxY(LevelHeightAccessor level) {
      return level.getMaxBuildHeight() - 1;
   }

   public static void setBlockState(ChunkAccess chunk, BlockPos position, BlockState state) {
      chunk.setBlockState(position, state, false);
   }

   public static <T> Registry<T> registry(RegistryAccess access, ResourceKey<? extends Registry<? extends T>> key) {
      return access.registryOrThrow(key);
   }

   public static WorldData worldData(WorldStem stem) {
      return stem.worldData();
   }

   // Level stems are saved from the registries in these releases, so only the registries change.
   public static WorldStem withRegistries(WorldStem stem, LayeredRegistryAccess<RegistryLayer> registries, UnaryOperator<LevelStem> levelStems) {
      return new WorldStem(stem.resourceManager(), stem.dataPackResources(), registries, stem.worldData());
   }

   public static <T> T registryValue(Registry<T> registry, ResourceKey<T> key) {
      return registry.getOrThrow(key);
   }

   public static <T> Holder<T> registryHolder(Registry<T> registry, ResourceKey<T> key) {
      return registry.getHolderOrThrow(key);
   }

   public static String keyPath(ResourceKey<?> key) {
      return key.location().getPath();
   }

   public static String keyId(ResourceKey<?> key) {
      return key.location().toString();
   }

   public static <T> String registryKeyPath(Registry<T> registry, T value) {
      ResourceLocation id = registry.getKey(value);
      return id == null ? null : id.getPath();
   }

   public static ServerBossEvent bossEvent(Component name, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay) {
      return new ServerBossEvent(name, color, overlay);
   }

   public static <T> CompletableFuture<T> supplyBackground(String taskName, Supplier<T> task) {
      return CompletableFuture.supplyAsync(Util.wrapThreadWithTaskName(taskName, task), Util.backgroundExecutor());
   }

   public static void snapTo(Entity entity, double x, double y, double z, float yRot, float xRot) {
      entity.moveTo(x, y, z, yRot, xRot);
   }

   public static CarvingMask carvingMask(ProtoChunk chunk) {
      return chunk.getCarvingMask(GenerationStep.Carving.AIR);
   }

   public static CarvingMask getOrCreateCarvingMask(ProtoChunk chunk) {
      return chunk.getOrCreateCarvingMask(GenerationStep.Carving.AIR);
   }

   public static void setCarvingMask(ProtoChunk chunk, CarvingMask mask) {
      chunk.setCarvingMask(GenerationStep.Carving.AIR, mask);
   }

   public static void copyCarvers(BiomeGenerationSettings original, BiomeGenerationSettings.PlainBuilder builder, Predicate<Holder<?>> keep) {
      for (GenerationStep.Carving step : GenerationStep.Carving.values()) {
         for (Holder<ConfiguredWorldCarver<?>> carver : original.getCarvers(step)) {
            Holder<ConfiguredWorldCarver<?>> safeCarver = Objects.requireNonNull(carver, "carver");
            if (keep.test(safeCarver)) {
               builder.addCarver(step, safeCarver);
            }
         }
      }
   }
}
