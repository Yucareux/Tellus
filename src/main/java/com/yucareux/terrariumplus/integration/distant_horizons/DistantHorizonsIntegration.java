package com.yucareux.terrariumplus.integration.distant_horizons;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.yucareux.terrariumplus.worldgen.EarthChunkGenerator;
import com.yucareux.terrariumplus.worldgen.EarthGeneratorSettings;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

public final class DistantHorizonsIntegration {
	private static final Logger LOGGER = LogUtils.getLogger();

	private DistantHorizonsIntegration() {
	}

	private static boolean checkApiVersion() {
		final int apiMajor = DhApi.getApiMajorVersion();
		final int apiMinor = DhApi.getApiMinorVersion();
		final int apiPatch = DhApi.getApiPatchVersion();
		if (apiMajor < 4) {
			LOGGER.warn(
					"Detected Distant Horizons {}, but API {}.{}.{} is too old - won't enable integration with TerrariumPlus",
					DhApi.getModVersion(),
					apiMajor,
					apiMinor,
					apiPatch
			);
			return false;
		}
		LOGGER.info(
				"Detected Distant Horizons {} (API {}.{}.{}), enabling integration with TerrariumPlus",
				DhApi.getModVersion(),
				apiMajor,
				apiMinor,
				apiPatch
		);
		return true;
	}

	public static void bootstrap() {
		if (!checkApiVersion()) {
			return;
		}
		DhApiEventRegister.on(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent() {
			@Override
			public void onLevelLoad(final DhApiEventParam<EventParam> param) {
				DistantHorizonsIntegration.onLevelLoad(param.value.levelWrapper);
			}
		});
	}

	private static void onLevelLoad(final IDhApiLevelWrapper levelWrapper) {
		if (levelWrapper.getWrappedMcObject() instanceof final ServerLevel level
				&& level.getChunkSource().getGenerator() instanceof final EarthChunkGenerator generator
		) {
			EarthGeneratorSettings settings = generator.settings();
			if (settings.distantHorizonsRenderMode() == EarthGeneratorSettings.DistantHorizonsRenderMode.DETAILED) {
				LOGGER.info("Distant Horizons render mode set to detailed; using chunk-based generator");
				final TerrariumChunkLodGenerator chunkGenerator = new TerrariumChunkLodGenerator(level);
				final DhApiResult<Void> result = DhApi.worldGenOverrides.registerWorldGeneratorOverride(levelWrapper, chunkGenerator);
				if (!result.success) {
					LOGGER.warn("Failed to register TerrariumPlus chunk LOD generator: {}", result.message);
				}
				return;
			}
			final TerrariumLodGenerator lodGenerator = new TerrariumLodGenerator(levelWrapper, generator);
			final DhApiResult<Void> result = DhApi.worldGenOverrides.registerWorldGeneratorOverride(levelWrapper, lodGenerator);
			if (!result.success) {
				LOGGER.warn("Failed to register TerrariumPlus LOD generator: {}", result.message);
			}
		}
	}
}
