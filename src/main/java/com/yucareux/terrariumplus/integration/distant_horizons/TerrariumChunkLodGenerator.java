package com.yucareux.terrariumplus.integration.distant_horizons;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.AbstractDhApiChunkWorldGenerator;
import com.seibel.distanthorizons.api.objects.data.DhApiChunk;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

public final class TerrariumChunkLodGenerator extends AbstractDhApiChunkWorldGenerator {
	private final ServerLevel level;

	public TerrariumChunkLodGenerator(ServerLevel level) {
		this.level = Objects.requireNonNull(level, "level");
	}

	@Override
	public EDhApiWorldGeneratorReturnType getReturnType() {
		return EDhApiWorldGeneratorReturnType.VANILLA_CHUNKS;
	}

	@Override
	public Object[] generateChunk(int chunkPosX, int chunkPosZ, EDhApiDistantGeneratorMode generatorMode) {
		LevelChunk chunk = level.getChunk(chunkPosX, chunkPosZ);
		return new Object[]{chunk, level};
	}

	@Override
	public DhApiChunk generateApiChunk(int chunkPosX, int chunkPosZ, EDhApiDistantGeneratorMode generatorMode) {
		throw new UnsupportedOperationException("TerrariumChunkLodGenerator uses vanilla chunks");
	}

	@Override
	public void preGeneratorTaskStart() {
	}

	@Override
	public void close() {
	}
}
