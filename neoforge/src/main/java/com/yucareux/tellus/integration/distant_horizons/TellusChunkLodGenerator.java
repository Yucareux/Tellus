package com.yucareux.tellus.integration.distant_horizons;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.AbstractDhApiChunkWorldGenerator;
import com.seibel.distanthorizons.api.objects.data.DhApiChunk;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

public final class TellusChunkLodGenerator extends AbstractDhApiChunkWorldGenerator {
   private final ServerLevel level;

   public TellusChunkLodGenerator(ServerLevel level) {
      this.level = Objects.requireNonNull(level, "level");
   }

   public EDhApiWorldGeneratorReturnType getReturnType() {
      return EDhApiWorldGeneratorReturnType.VANILLA_CHUNKS;
   }

   public Object[] generateChunk(int chunkPosX, int chunkPosZ, EDhApiDistantGeneratorMode generatorMode) {
      LevelChunk chunk = this.level.getChunk(chunkPosX, chunkPosZ);
      return new Object[]{chunk, this.level};
   }

   public DhApiChunk generateApiChunk(int chunkPosX, int chunkPosZ, EDhApiDistantGeneratorMode generatorMode) {
      throw new UnsupportedOperationException("TellusChunkLodGenerator uses vanilla chunks");
   }

   public void preGeneratorTaskStart() {
   }

   public void close() {
   }
}
