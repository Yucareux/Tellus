package com.yucareux.tellus.compat;

import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.HighYPackedCoordinateProfile;
import com.yucareux.tellus.worldgen.RandomBiomeCatalog;
import java.util.List;
import java.util.concurrent.Executor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Util;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;

/** Narrow compatibility seam for Minecraft 1.21.11's transitional APIs. */
public final class MinecraftVersionCompat {
   private MinecraftVersionCompat() {
   }

   public static Executor backgroundExecutor() {
      return Util.backgroundExecutor();
   }

   public static List<String> defaultRandomBiomeIds() {
      return RandomBiomeCatalog.minecraft1_21_11OverworldBiomeIds();
   }

   public static List<String> normalizeRandomBiomeSelection(List<String> biomeIds) {
      return RandomBiomeCatalog.normalizeMinecraft1_21_11Selection(biomeIds);
   }

   public static DimensionType applyHeightLimits(DimensionType base, EarthGeneratorSettings.HeightLimits limits) {
      return new DimensionType(
         base.hasFixedTime(),
         base.hasSkyLight(),
         base.hasCeiling(),
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
         base.timelines()
      );
   }

   public static HolderSet<Block> overworldCarverReplaceables(Registry<Block> blockRegistry) {
      return blockRegistry.getOrThrow(BlockTags.OVERWORLD_CARVER_REPLACEABLES);
   }

   public static DensityFunctions.BeardifierOrMarker emptyBeardifier() {
      return Beardifier.EMPTY;
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
      return pos.x;
   }

   public static int chunkZ(ChunkPos pos) {
      return pos.z;
   }

   public static long packChunkPos(int chunkX, int chunkZ) {
      return ChunkPos.asLong(chunkX, chunkZ);
   }

   public static ChunkPos chunkPosContaining(BlockPos position) {
      return new ChunkPos(position);
   }

   public static void markPosForPostProcessing(ChunkAccess chunk, BlockPos position) {
      chunk.markPosForPostprocessing(position);
   }

   public static ConfiguredFeature<?, ?> unwrapConfiguredFeature(Object configuredFeature) {
      return (ConfiguredFeature<?, ?>)configuredFeature;
   }

   public static Axolotl createAxolotl(ServerLevel level, BlockPos position) {
      return EntityType.AXOLOTL.create(level, entity -> {}, position, EntitySpawnReason.CHUNK_GENERATION, false, false);
   }

   public static ServerLevel serverLevel(ServerPlayer player) {
      return player.level();
   }

   public static int maxBuildHeight(WorldGenLevel level) {
      return level.getMaxY();
   }

   public static boolean isInsideBuildHeight(WorldGenLevel level, BlockPos position) {
      return !level.isOutsideBuildHeight(position);
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
}
