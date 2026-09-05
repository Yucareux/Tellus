package com.yucareux.tellus.compat;

import com.google.gson.JsonObject;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder.Reference;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.permissions.Permission.HasCommandLevel;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biome.Precipitation;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelData.RespawnData;

/** Minecraft 1.21.11 server API bridge for the shared Tellus bootstrap. */
public final class TellusMinecraftCompat {
   private TellusMinecraftCompat() {
   }

   public static boolean hasGamemasterPermission(CommandSourceStack source) {
      return source.permissions().hasPermission(new HasCommandLevel(PermissionLevel.GAMEMASTERS));
   }

   public static void configureInitialSpawn(ServerLevel level, EarthChunkGenerator generator) {
      BlockPos spawn = generator.getInitialSpawnPosition(level);
      level.setRespawnData(RespawnData.of(level.dimension(), spawn, 0.0F, 0.0F));
   }

   public static boolean vanillaPrecipitationIsSnow(Biome biome, BlockPos position, ServerLevel level) {
      return biome.getPrecipitationAt(position, level.getSeaLevel()) == Precipitation.SNOW;
   }

   public static long dayTime(ServerLevel level) {
      return Math.floorMod(level.getDayTime(), 24_000L);
   }

   public static LevelStem overworldStem(MinecraftServer server) {
      Registry<LevelStem> stems = server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM);
      Optional<Reference<LevelStem>> stem = stems.get(LevelStem.OVERWORLD);
      return stem.map(Reference::value).orElse(null);
   }

   public static void validateDynamicHeight(EarthGeneratorSettings settings, EarthGeneratorSettings.HeightLimits limits) {
      // Minecraft 1.21.11 retains the pre-26 packed-height constraints.
   }

   public static String dimensionNamespace(ResourceKey<DimensionType> key) {
      return key.identifier().getNamespace();
   }

   public static String dimensionPath(ResourceKey<DimensionType> key) {
      return key.identifier().getPath();
   }

   public static void writePackFormat(JsonObject pack) {
      int packFormat = SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA).major();
      pack.addProperty("pack_format", packFormat);
      pack.addProperty("min_format", packFormat);
      pack.addProperty("max_format", packFormat);
   }
}
