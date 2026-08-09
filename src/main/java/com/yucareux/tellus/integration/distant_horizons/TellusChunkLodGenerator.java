package com.yucareux.tellus.integration.distant_horizons;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.AbstractDhApiChunkWorldGenerator;
import com.seibel.distanthorizons.api.objects.data.DhApiChunk;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainAvailability;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainCompatibility;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;

public final class TellusChunkLodGenerator extends AbstractDhApiChunkWorldGenerator {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final boolean LOD_TIMING_LOGGING = Boolean.parseBoolean(
      System.getProperty("tellus.dhLodTiming", System.getProperty("tellus.lodTiming", "false"))
   );
   private static final long LOD_TIMING_THRESHOLD_NS = TimeUnit.MILLISECONDS.toNanos(
      Math.max(0L, Long.getLong("tellus.dhLodTimingThresholdMs", 0L))
   );
   private final ServerLevel level;
   private final EarthChunkGenerator generator;
   private final String managedTerrainKey;

   public TellusChunkLodGenerator(ServerLevel level) {
      this.level = Objects.requireNonNull(level, "level");
      this.generator = (EarthChunkGenerator)level.getChunkSource().getGenerator();
      this.managedTerrainKey = ManagedTerrainAvailability.key(this.generator);
   }

   public static boolean isTimingEnabled() {
      return LOD_TIMING_LOGGING;
   }

   public EDhApiWorldGeneratorReturnType getReturnType() {
      return EDhApiWorldGeneratorReturnType.VANILLA_CHUNKS;
   }

   public byte getGenerationAvailability(int chunkPosMinX, int chunkPosMinZ, int widthChunks, byte targetDataDetail) {
      if (!DistantHorizonsIntegration.isDistantGenerationReady()) {
         return ManagedTerrainAvailability.WAIT;
      }
      return this.managedDownloadsActive()
         ? ManagedTerrainAvailability.availability(this.managedTerrainKey, chunkPosMinX, chunkPosMinZ, widthChunks)
         : ManagedTerrainAvailability.READY;
   }

   private boolean managedDownloadsActive() {
      return this.generator.settings().tellusManagedTerrainDownloads() && ManagedTerrainCompatibility.isGenerationGateAvailable();
   }

   public Object[] generateChunk(int chunkPosX, int chunkPosZ, EDhApiDistantGeneratorMode generatorMode) {
      long startNanos = LOD_TIMING_LOGGING ? System.nanoTime() : 0L;
      boolean success = false;

      try {
         DistantHorizonsIntegration.awaitDistantGenerationReady();
         ManagedTerrainNetworkPolicy.Scope networkScope = this.managedDownloadsActive()
            ? ManagedTerrainNetworkPolicy.cacheOnly()
            : null;
         try (networkScope) {
            LevelChunk chunk = this.level.getChunk(chunkPosX, chunkPosZ);
            success = true;
            return new Object[]{chunk, this.level};
         }
      } finally {
         if (LOD_TIMING_LOGGING) {
            long elapsedNanos = System.nanoTime() - startNanos;
            if (elapsedNanos >= LOD_TIMING_THRESHOLD_NS) {
               LOGGER.info(
                  "DH LOD timing status={} path=chunk chunk=[{}, {}] genMode={} total={}ms phases={{chunkLoad={}ms}}",
                  success ? "success" : "failed",
                  chunkPosX,
                  chunkPosZ,
                  generatorMode,
                  formatMillis(elapsedNanos),
                  formatMillis(elapsedNanos)
               );
            }
         }
      }
   }

   private static String formatMillis(long nanos) {
      return String.format(java.util.Locale.ROOT, "%.3f", (double)nanos / 1000000.0);
   }

   public DhApiChunk generateApiChunk(int chunkPosX, int chunkPosZ, EDhApiDistantGeneratorMode generatorMode) {
      throw new UnsupportedOperationException("TellusChunkLodGenerator uses vanilla chunks");
   }

   public void preGeneratorTaskStart() {
   }

   public void close() {
   }
}
