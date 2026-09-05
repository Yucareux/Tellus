package com.yucareux.tellus.mixin;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.data.worldgen.features.MiscOverworldFeatures;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.storage.LevelData.RespawnData;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerInitialSpawnMixin {
   @Inject(
      method = "setInitialSpawn",
      at = @At("HEAD"),
      cancellable = true
   )
   private static void tellus$setEarthInitialSpawn(
      ServerLevel level,
      ServerLevelData levelData,
      boolean generateBonusChest,
      boolean debugWorld,
      LevelLoadListener listener,
      CallbackInfo ci
   ) {
      ChunkGenerator generator = level.getChunkSource().getGenerator();
      if (!(generator instanceof EarthChunkGenerator earthGenerator)) {
         return;
      }

      BlockPos spawn = earthGenerator.getInitialSpawnPosition(level);
      listener.start(LevelLoadListener.Stage.PREPARE_GLOBAL_SPAWN, 0);
      listener.updateFocus(level.dimension(), MinecraftVersionCompat.chunkPosContaining(spawn));
      levelData.setSpawn(RespawnData.of(level.dimension(), spawn, 0.0F, 0.0F));
      if (generateBonusChest) {
         level.registryAccess()
            .lookup(Registries.CONFIGURED_FEATURE)
            .flatMap(registry -> registry.get(MiscOverworldFeatures.BONUS_CHEST))
            .ifPresent(
               feature -> feature.value().place(level, generator, level.getRandom(), levelData.getRespawnData().pos())
            );
      }

      listener.finish(LevelLoadListener.Stage.PREPARE_GLOBAL_SPAWN);
      Tellus.LOGGER.info("Using Tellus initial spawn at {} and skipping vanilla spawn search.", spawn);
      ci.cancel();
   }
}
