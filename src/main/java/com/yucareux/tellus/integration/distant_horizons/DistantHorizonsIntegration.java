package com.yucareux.tellus.integration.distant_horizons;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiGrassSideRendering;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiGeneratorPlan;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBlockColorOverrideEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBlockStateWrapperCreatedEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelUnloadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.yucareux.tellus.platform.TellusPlatform;
import com.yucareux.tellus.worldgen.EarthBiomeSource;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainCompatibility;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.slf4j.Logger;

public final class DistantHorizonsIntegration {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final String VOXY_MOD_ID = "voxy";
   private static final int DEFAULT_FOLIAGE_COLOR = 0x48B518;
   private static final int BIRCH_FOLIAGE_COLOR = 0x80A755;
   private static final int SPRUCE_FOLIAGE_COLOR = 0x619961;
   private static final int OAK_LEAVES_TEXTURE_COLOR = 0x909090;
   private static final int BIRCH_LEAVES_TEXTURE_COLOR = 0x838182;
   private static final int SPRUCE_LEAVES_TEXTURE_COLOR = 0x7E7E7E;
   private static final int JUNGLE_LEAVES_TEXTURE_COLOR = 0x9D9A90;
   private static final int ACACIA_LEAVES_TEXTURE_COLOR = 0x959595;
   private static final int DARK_OAK_LEAVES_TEXTURE_COLOR = 0x979797;
   private static final int MANGROVE_LEAVES_TEXTURE_COLOR = 0x828181;
   private static final int CHERRY_LEAVES_TEXTURE_COLOR = 0xE5ADC2;
   private static final int PALE_OAK_LEAVES_TEXTURE_COLOR = 0x757A73;
   private static final int AZALEA_LEAVES_TEXTURE_COLOR = 0x5A732C;
   private static final int FLOWERING_AZALEA_LEAVES_TEXTURE_COLOR = 0x646F3D;
   private static final Block PALE_OAK_LEAVES_BLOCK = blockByField("PALE_OAK_LEAVES");
   private static final String DH_QUALITY_CONFIG_CLASS = "com.seibel.distanthorizons.core.config.Config$Client$Advanced$Graphics$Quality";
   private static final String DH_WORLD_GENERATOR_CONFIG_CLASS = "com.seibel.distanthorizons.core.config.Config$Common$WorldGenerator";
   private static final Map<String, EarthChunkGenerator> TELLUS_GENERATORS_BY_DIMENSION = new ConcurrentHashMap<>();
   private static final Set<String> DIRECT_LOD_DIMENSIONS = new HashSet<>();
   private static final List<RuntimeConfigOverride> ACTIVE_RUNTIME_OVERRIDES = new ArrayList<>();
   private static final DistantHorizonsStartupGate STARTUP_GATE = new DistantHorizonsStartupGate();
   private static volatile Integer dhMaxWorldYSize;

   private DistantHorizonsIntegration() {
   }

   private static boolean checkApiVersion() {
      int apiMajor = DhApi.getApiMajorVersion();
      int apiMinor = DhApi.getApiMinorVersion();
      int apiPatch = DhApi.getApiPatchVersion();
      if (apiMajor < 7 || apiMajor == 7 && apiMinor < 1) {
         LOGGER.warn(
            "Detected Distant Horizons {}, but API {}.{}.{} is too old - won't enable integration with Tellus",
            new Object[]{DhApi.getModVersion(), apiMajor, apiMinor, apiPatch}
         );
         return false;
      } else {
         LOGGER.info(
            "Detected Distant Horizons {} (API {}.{}.{}), enabling integration with Tellus", new Object[]{DhApi.getModVersion(), apiMajor, apiMinor, apiPatch}
         );
         return true;
      }
   }

   public static void bootstrap() {
      registerStartupGateEvents();
      boolean gateAvailable = generationGateAvailable();
      ManagedTerrainCompatibility.setDistantHorizonsCompatibility(true, gateAvailable);
      if (!gateAvailable) {
         LOGGER.warn(
            "Tellus-managed terrain downloads are unavailable with this Distant Horizons build; "
               + "worlds will use the legacy download path after startup generation is released"
         );
      }
      boolean apiSupported = checkApiVersion();
      DistantHorizonsCompatibility.record(apiSupported, gateAvailable);
      if (apiSupported) {
         DhApiEventRegister.on(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent() {
            public void onLevelLoad(DhApiEventParam<DhApiLevelLoadEvent.EventParam> param) {
               DistantHorizonsIntegration.onLevelLoad(param.value.levelWrapper);
            }
         });
         DhApiEventRegister.on(DhApiLevelUnloadEvent.class, new DhApiLevelUnloadEvent() {
            public void onLevelUnload(DhApiEventParam<DhApiLevelUnloadEvent.EventParam> param) {
               DistantHorizonsIntegration.onLevelUnload(param.value.levelWrapper);
            }
         });
         registerLeafColorOverrideEvents();
      }
   }

   private static void registerStartupGateEvents() {
      STARTUP_GATE.reset();
      TellusPlatform.registerDistantHorizonsLifecycle(
         STARTUP_GATE::reset,
         STARTUP_GATE::reset,
         () -> {
            if (STARTUP_GATE.release()) {
               LOGGER.info("Released Tellus DH generation after initial player position became available");
            }
         }
      );
   }

   static boolean isDistantGenerationReady() {
      return STARTUP_GATE.isReady();
   }

   static CompletableFuture<Void> distantGenerationReadyFuture() {
      return STARTUP_GATE.whenReady();
   }

   static void awaitDistantGenerationReady() {
      try {
         if (!STARTUP_GATE.awaitReady()) {
            throw new java.util.concurrent.CancellationException("Tellus DH startup gate reset before release");
         }
      } catch (InterruptedException error) {
         Thread.currentThread().interrupt();
         throw new java.util.concurrent.CancellationException("Tellus DH startup gate interrupted");
      }
   }

   private static boolean generationGateAvailable() {
      try {
         IDhApiWorldGenerator.class.getMethod("getGenerationAvailability", int.class, int.class, int.class, byte.class);
         return true;
      } catch (NoSuchMethodException | LinkageError error) {
         return false;
      }
   }

   private static void onLevelLoad(IDhApiLevelWrapper levelWrapper) {
      if (levelWrapper.getWrappedMcObject() instanceof ServerLevel level && level.getChunkSource().getGenerator() instanceof EarthChunkGenerator generator) {
         EarthGeneratorSettings settings = generator.settings();
         TELLUS_GENERATORS_BY_DIMENSION.put(levelWrapper.getDimensionName(), generator);
         if (settings.voxyChunkPregenEnabled() && TellusPlatform.isModLoaded(VOXY_MOD_ID)) {
            LOGGER.info("Voxy pregen enabled; skipping Tellus Distant Horizons integration override");
            return;
         }

         EDhApiDistantGeneratorMode dhGeneratorMode = currentDistantGeneratorMode();
         LOGGER.info(
            "Tellus DH LOD setup dimension={} renderMode={} generatorMode={}",
            levelWrapper.getDimensionName(),
            settings.distantHorizonsRenderMode().id(),
            dhGeneratorMode
         );
         if (dhGeneratorMode == EDhApiDistantGeneratorMode.PRE_EXISTING_ONLY) {
            LOGGER.info("Distant Horizons generator mode set to pre-existing only; skipping Tellus generator override");
            return;
         }

         if (shouldUseChunkLodGenerator(generator, dhGeneratorMode)) {
            if (settings.distantHorizonsRenderMode() == EarthGeneratorSettings.DistantHorizonsRenderMode.DETAILED) {
               LOGGER.info("Distant Horizons render mode set to detailed; using chunk-based generator");
            } else {
               LOGGER.info("Distant Horizons generator mode set to internal server; using chunk-based generator");
            }

            TellusChunkLodGenerator chunkGenerator = new TellusChunkLodGenerator(level);
            DhApiResult<Void> result = DhApi.worldGenOverrides.registerWorldGeneratorOverride(levelWrapper, chunkGenerator);
            if (!result.success) {
               LOGGER.warn("Failed to register Tellus chunk LOD generator: {}", result.message);
            } else {
               LOGGER.info("Registered Tellus DH LOD generator path=chunk timingEnabled={}", TellusChunkLodGenerator.isTimingEnabled());
            }

            return;
         }

         // DH 3.3 supports N-sized requests directly; the old experimental override no longer exists.
         TellusLodGenerator lodGenerator = new TellusLodGenerator(levelWrapper, generator);
         DhApiResult<Void> result = DhApi.worldGenOverrides.registerWorldGeneratorOverride(levelWrapper, lodGenerator);
         if (!result.success) {
            lodGenerator.close();
            LOGGER.warn("Failed to register Tellus LOD generator: {}", result.message);
         } else {
            LOGGER.info("Registered Tellus DH LOD generator path=direct timingEnabled={}", TellusLodGenerator.isTimingEnabled());
            acquireDirectLodOverrides(levelWrapper.getDimensionName());
         }
      }
   }

   private static void onLevelUnload(IDhApiLevelWrapper levelWrapper) {
      String dimensionName = levelWrapper.getDimensionName();
      TELLUS_GENERATORS_BY_DIMENSION.remove(dimensionName);
      if (levelWrapper.getWrappedMcObject() instanceof ServerLevel) {
         releaseDirectLodOverrides(dimensionName);
      }
   }

   /**
    * DH settings that the direct generator changes at runtime (DH's API value) while it generates LODs in
    * this game. Each saved setting is untouched, and a player's explicit non-default choice is left alone.
    */
   private static List<RuntimeConfigOverride> directLodOverrides() {
      return List.of(
         // Grass-capped direct LOD columns are grass all the way down, but DH fades the sides of grass LODs
         // to dirt by default, which turns distant grassy slopes brown again.
         new RuntimeConfigOverride(
            DH_QUALITY_CONFIG_CLASS, "grassSideRendering", EDhApiGrassSideRendering.FADE_TO_DIRT, EDhApiGrassSideRendering.AS_GRASS,
            "grass LOD sides render as grass"
         ),
         // DH pauses world generation while the integrated server's average tick over the last 100 ticks is
         // 10 ms or more (or two ticks reach 30 ms), so vanilla chunk generation cannot lag the server. The
         // direct generator never touches the server thread, but a loaded 1:1 Tellus world ticks at about
         // 10-12 ms by itself, and the gate kept LOD generation paused nearly all the time in game.
         new RuntimeConfigOverride(
            DH_WORLD_GENERATOR_CONFIG_CLASS, "limitIfServerUnhealthy", Boolean.TRUE, Boolean.FALSE,
            "world generation no longer pauses for a busy integrated server"
         ),
         // DH also pauses world generation and the update propagator while the camera averages more than
         // 20 blocks/s (rocket-elytra flight is 30 or more), leaving the CPU to loading LODs that already
         // exist. Direct Tellus LODs only exist once generated, so during a flight nothing ahead gained
         // detail and areas left behind lost their coarser LODs.
         new RuntimeConfigOverride(
            DH_WORLD_GENERATOR_CONFIG_CLASS, "pauseIfMovingQuickly", Boolean.TRUE, Boolean.FALSE,
            "world generation no longer pauses for fast camera movement"
         ),
         // DH's default plan follows its fast surface pass with a second pass that regenerates every
         // section at full block detail across the whole render distance, meant to add the trees and
         // structures a rough surface lacks. Direct Tellus LODs already have them at every detail, so the
         // second pass only redid the finished render distance at 1x1 blocks (about 3 million sections at a
         // 4096-chunk radius) and replaced the fitted coarse LODs with down-sampled ones.
         new RuntimeConfigOverride(
            DH_WORLD_GENERATOR_CONFIG_CLASS, "generatorPlan", EDhApiGeneratorPlan.SURFACE_THEN_CHUNKS, EDhApiGeneratorPlan.SURFACE_ONLY,
            "LODs are generated once at their own detail (no full-detail second pass)"
         )
      );
   }

   private static void acquireDirectLodOverrides(String dimensionName) {
      synchronized (DIRECT_LOD_DIMENSIONS) {
         if (!DIRECT_LOD_DIMENSIONS.add(dimensionName) || DIRECT_LOD_DIMENSIONS.size() > 1) {
            return;
         }

         for (RuntimeConfigOverride override : directLodOverrides()) {
            if (override.acquire()) {
               ACTIVE_RUNTIME_OVERRIDES.add(override);
            }
         }
      }
   }

   private static void releaseDirectLodOverrides(String dimensionName) {
      synchronized (DIRECT_LOD_DIMENSIONS) {
         if (!DIRECT_LOD_DIMENSIONS.remove(dimensionName) || !DIRECT_LOD_DIMENSIONS.isEmpty()) {
            return;
         }

         for (RuntimeConfigOverride override : ACTIVE_RUNTIME_OVERRIDES) {
            override.release();
         }
         ACTIVE_RUNTIME_OVERRIDES.clear();
      }
   }

   /** One DH config entry set through its API value, only when the player kept the given default. */
   private static final class RuntimeConfigOverride {
      private final String configClass;
      private final String field;
      private final Object expectedValue;
      private final Object overrideValue;
      private final String description;
      private Object entry;

      private RuntimeConfigOverride(String configClass, String field, Object expectedValue, Object overrideValue, String description) {
         this.configClass = configClass;
         this.field = field;
         this.expectedValue = expectedValue;
         this.overrideValue = overrideValue;
         this.description = description;
      }

      private boolean acquire() {
         try {
            Object configEntry = Class.forName(this.configClass).getField(this.field).get(null);
            Class<?> entryClass = configEntry.getClass();
            if (this.expectedValue.equals(entryClass.getMethod("getTrueValue").invoke(configEntry))
               && entryClass.getMethod("getApiValue").invoke(configEntry) == null) {
               setApiValue(configEntry, this.overrideValue);
               this.entry = configEntry;
               LOGGER.info("Distant Horizons: {} while Tellus generates LODs (runtime only; config unchanged)", this.description);
               return true;
            }
         } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            LOGGER.debug("Distant Horizons setting {} is unavailable; Tellus leaves it to DH", this.field, error);
         }
         return false;
      }

      private void release() {
         Object configEntry = this.entry;
         this.entry = null;
         if (configEntry == null) {
            return;
         }
         try {
            Class<?> entryClass = configEntry.getClass();
            if (this.overrideValue.equals(entryClass.getMethod("getApiValue").invoke(configEntry))) {
               setApiValue(configEntry, null);
            }
         } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            LOGGER.warn("Failed to restore Distant Horizons setting {}", this.field, error);
         }
      }

      /**
       * DH 3.3.2 added the name of the mod setting an API value (its config screen shows it) and dropped the
       * value-only setter, which older releases still need.
       */
      private static void setApiValue(Object configEntry, Object value) throws ReflectiveOperationException {
         Class<?> entryClass = configEntry.getClass();
         Method setter;
         try {
            setter = entryClass.getMethod("setApiValue", Object.class, String.class);
         } catch (NoSuchMethodException error) {
            entryClass.getMethod("setApiValue", Object.class).invoke(configEntry, value);
            return;
         }
         setter.invoke(configEntry, value, "Tellus");
      }
   }

   public static boolean shouldSkipChunkBackedUpdates(Object dhLevel) {
      String dimensionName = dimensionNameFromDhLevel(dhLevel);
      if (dimensionName == null) {
         return false;
      }

      EarthChunkGenerator generator = TELLUS_GENERATORS_BY_DIMENSION.get(dimensionName);
      return generator != null && generator.settings().experimentalIncreaseHeight() && !supportsExperimentalGeneratorHeight(generator);
   }

   public static boolean supportsExperimentalGeneratorHeight(EarthChunkGenerator generator) {
      EarthGeneratorSettings settings = generator.settings();
      return !settings.experimentalIncreaseHeight() || distantHorizonsMaxWorldYSize() >= generator.getGenDepth();
   }

   private static int distantHorizonsMaxWorldYSize() {
      Integer cachedMax = dhMaxWorldYSize;
      if (cachedMax != null) {
         return cachedMax;
      }

      int detectedMax = 6095;
      try {
         Class<?> renderDataPointUtil = Class.forName("com.seibel.distanthorizons.core.util.RenderDataPointUtil");
         detectedMax = renderDataPointUtil.getField("MAX_WORLD_Y_SIZE").getInt(null);
      } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
         LOGGER.debug("Failed to read Distant Horizons render Y range", error);
      }

      dhMaxWorldYSize = detectedMax;
      return detectedMax;
   }

   private static String dimensionNameFromDhLevel(Object dhLevel) {
      if (dhLevel == null) {
         return null;
      }

      try {
         Method getLevelWrapper = dhLevel.getClass().getMethod("getLevelWrapper");
         Object levelWrapper = getLevelWrapper.invoke(dhLevel);
         if (levelWrapper == null) {
            return null;
         }

         Method getDimensionName = levelWrapper.getClass().getMethod("getDimensionName");
         Object dimensionName = getDimensionName.invoke(levelWrapper);
         return dimensionName instanceof String name ? name : null;
      } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
         return null;
      }
   }

   private static void registerLeafColorOverrideEvents() {
      DhApiEventRegister.on(DhApiBlockStateWrapperCreatedEvent.class, new DhApiBlockStateWrapperCreatedEvent() {
         public void blockStateWrapperCreated(DhApiEventParam<DhApiBlockStateWrapperCreatedEvent.EventParam> event) {
            IDhApiBlockStateWrapper wrapper = event.value.getBlockStateWrapper();
            if (!(wrapper.getWrappedMcObject() instanceof BlockState blockState)) {
               return;
            }
            if (isCorrectedLodLeafBlock(blockState.getBlock())) {
               event.value.setAllowApiColorOverride(true);
            } else if (isMisclassifiedLodLight(blockState)) {
               event.value.setBlockMaterial(EDhApiBlockMaterial.ILLUMINATED);
            }
         }
      });
      DhApiEventRegister.on(DhApiBlockColorOverrideEvent.class, new DhApiBlockColorOverrideEvent() {
         public void onBlockColorOverridden(DhApiEventParam<DhApiBlockColorOverrideEvent.EventParam> event) {
            DistantHorizonsIntegration.overrideLeafColor(event.value);
         }
      });
   }

   private static void overrideLeafColor(DhApiBlockColorOverrideEvent.EventParam event) {
      IDhApiLevelWrapper levelWrapper = event.getLevelWrapper();
      if (levelWrapper == null || !(event.getBlockStateWrapper().getWrappedMcObject() instanceof BlockState blockState)) {
         return;
      }

      EarthChunkGenerator generator = TELLUS_GENERATORS_BY_DIMENSION.get(levelWrapper.getDimensionName());
      if (generator == null) {
         return;
      }

      int color = correctedLeafColor(blockState.getBlock(), generator, event);
      if (color >= 0) {
         event.setColor(event.getAlpha(), red(color), green(color), blue(color));
      }
   }

   private static int correctedLeafColor(Block block, EarthChunkGenerator generator, DhApiBlockColorOverrideEvent.EventParam event) {
      int textureColor = leafTextureColor(block);
      if (textureColor < 0) {
         return -1;
      } else if (isUntintedLeafBlock(block)) {
         return textureColor;
      }

      if (block == Blocks.BIRCH_LEAVES) {
         return multiplyRgb(textureColor, BIRCH_FOLIAGE_COLOR);
      } else if (block == Blocks.SPRUCE_LEAVES) {
         return multiplyRgb(textureColor, SPRUCE_FOLIAGE_COLOR);
      }

      return multiplyRgb(textureColor, lodFoliageColor(generator, event));
   }

   /**
    * The foliage tint of the LOD column's own biome, which DH passes with the event and also tints the
    * column's grass with. DH fires this event for every leaf on every LOD render build, so resolving the
    * block's biome again (the full surface pipeline, water and hydrology included) kept DH's render threads
    * busy and starved its world generation in game.
    */
   private static int lodFoliageColor(EarthChunkGenerator generator, DhApiBlockColorOverrideEvent.EventParam event) {
      IDhApiBiomeWrapper biomeWrapper = event.getBiomeWrapper();
      if (biomeWrapper != null && biomeWrapper.getWrappedMcObject() instanceof Holder<?> holder && holder.value() instanceof Biome biome) {
         return biome.getFoliageColor();
      }
      return tellusFoliageColor(generator, event.getBlockPosX(), event.getBlockPosZ());
   }

   private static int tellusFoliageColor(EarthChunkGenerator generator, int blockX, int blockZ) {
      try {
         if (generator.getBiomeSource() instanceof EarthBiomeSource earthBiomeSource) {
            Holder<Biome> biome = earthBiomeSource.getBiomeAtBlock(blockX, blockZ);
            return biome.value().getFoliageColor();
         }
      } catch (RuntimeException error) {
         return DEFAULT_FOLIAGE_COLOR;
      }

      return DEFAULT_FOLIAGE_COLOR;
   }

   private static int leafTextureColor(Block block) {
      if (block == Blocks.OAK_LEAVES) {
         return OAK_LEAVES_TEXTURE_COLOR;
      } else if (block == Blocks.BIRCH_LEAVES) {
         return BIRCH_LEAVES_TEXTURE_COLOR;
      } else if (block == Blocks.SPRUCE_LEAVES) {
         return SPRUCE_LEAVES_TEXTURE_COLOR;
      } else if (block == Blocks.JUNGLE_LEAVES) {
         return JUNGLE_LEAVES_TEXTURE_COLOR;
      } else if (block == Blocks.ACACIA_LEAVES) {
         return ACACIA_LEAVES_TEXTURE_COLOR;
      } else if (block == Blocks.DARK_OAK_LEAVES) {
         return DARK_OAK_LEAVES_TEXTURE_COLOR;
      } else if (block == Blocks.MANGROVE_LEAVES) {
         return MANGROVE_LEAVES_TEXTURE_COLOR;
      } else if (block == Blocks.CHERRY_LEAVES) {
         return CHERRY_LEAVES_TEXTURE_COLOR;
      } else if (block == PALE_OAK_LEAVES_BLOCK) {
         return PALE_OAK_LEAVES_TEXTURE_COLOR;
      } else if (block == Blocks.AZALEA_LEAVES) {
         return AZALEA_LEAVES_TEXTURE_COLOR;
      } else if (block == Blocks.FLOWERING_AZALEA_LEAVES) {
         return FLOWERING_AZALEA_LEAVES_TEXTURE_COLOR;
      }

      return -1;
   }

   private static boolean isUntintedLeafBlock(Block block) {
      return block == Blocks.CHERRY_LEAVES
         || block == PALE_OAK_LEAVES_BLOCK
         || block == Blocks.AZALEA_LEAVES
         || block == Blocks.FLOWERING_AZALEA_LEAVES;
   }

   /**
    * Light sources that DH files under stone because "stone" is in their name. Shader packs only make
    * illuminated LODs glow, so lit LOD windows (glowstone) and lamps would stay dull.
    */
   private static boolean isMisclassifiedLodLight(BlockState blockState) {
      return blockState.is(Blocks.GLOWSTONE) || blockState.is(Blocks.REDSTONE_LAMP) && blockState.getValue(BlockStateProperties.LIT);
   }

   private static boolean isCorrectedLodLeafBlock(Block block) {
      return block == Blocks.OAK_LEAVES
         || block == Blocks.BIRCH_LEAVES
         || block == Blocks.SPRUCE_LEAVES
         || block == Blocks.JUNGLE_LEAVES
         || block == Blocks.ACACIA_LEAVES
         || block == Blocks.DARK_OAK_LEAVES
         || block == Blocks.MANGROVE_LEAVES
         || block == Blocks.CHERRY_LEAVES
         || block == PALE_OAK_LEAVES_BLOCK
         || block == Blocks.AZALEA_LEAVES
         || block == Blocks.FLOWERING_AZALEA_LEAVES;
   }

   private static int multiplyRgb(int textureColor, int tintColor) {
      int red = multiplyColorChannel(red(textureColor), red(tintColor));
      int green = multiplyColorChannel(green(textureColor), green(tintColor));
      int blue = multiplyColorChannel(blue(textureColor), blue(tintColor));
      return red << 16 | green << 8 | blue;
   }

   private static int multiplyColorChannel(int textureChannel, int tintChannel) {
      return (textureChannel * tintChannel + 127) / 255;
   }

   private static int red(int color) {
      return color >> 16 & 0xFF;
   }

   private static int green(int color) {
      return color >> 8 & 0xFF;
   }

   private static int blue(int color) {
      return color & 0xFF;
   }

   private static Block blockByField(String fieldName) {
      try {
         Object value = Blocks.class.getField(fieldName).get(null);
         return value instanceof Block block ? block : null;
      } catch (IllegalAccessException | NoSuchFieldException error) {
         return null;
      }
   }

   private static boolean shouldUseChunkLodGenerator(EarthChunkGenerator generator, EDhApiDistantGeneratorMode dhGeneratorMode) {
      EarthGeneratorSettings settings = generator.settings();
      if (settings.experimentalIncreaseHeight() && !supportsExperimentalGeneratorHeight(generator)) {
         return false;
      }

      return settings.distantHorizonsRenderMode() == EarthGeneratorSettings.DistantHorizonsRenderMode.DETAILED
         || dhGeneratorMode == EDhApiDistantGeneratorMode.INTERNAL_SERVER;
   }

   private static EDhApiDistantGeneratorMode currentDistantGeneratorMode() {
      try {
         return DhApi.Delayed.configs == null ? null : DhApi.Delayed.configs.worldGenerator().chunkGeneratorMode().getValue();
      } catch (RuntimeException error) {
         LOGGER.debug("Failed to read Distant Horizons generator mode", error);
         return null;
      }
   }
}
