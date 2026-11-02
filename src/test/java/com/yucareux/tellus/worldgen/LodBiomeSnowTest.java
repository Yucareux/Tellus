package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.yucareux.tellus.compat.MinecraftRelease;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.world.data.osm.OsmQueryMode;
import com.yucareux.tellus.world.data.elevation.TerrainSlopeFixture;
import com.yucareux.tellus.world.data.elevation.TiltedTerrainFixture;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LodBiomeSnowTest {
   // About 3,600 m on the equator at 1:30: above the highland anchor, below alpine.
   private static final int HIGHLAND_SURFACE_Y = 184;

   @TempDir
   static Path gameDirectory;

   private static HolderLookup<Biome> biomes;
   private static String previousGameDir;
   private static String previousConfigDir;
   private static ClassLoader previousContextClassLoader;
   private static final Map<Holder.Reference<Block>, Object> ORIGINAL_BLOCK_TAGS = new HashMap<>();
   private static Field tagsField;

   @BeforeAll
   @SuppressWarnings("deprecation") // Binding tags under plain JUnit needs each block's built-in holder.
   static void bootstrapMinecraft() throws Exception {
      previousGameDir = System.getProperty("tellus.gameDir");
      previousConfigDir = System.getProperty("tellus.configDir");
      previousContextClassLoader = Thread.currentThread().getContextClassLoader();
      assumeFalse(
         LodBiomeSnowTest.class.getClassLoader().getResource("net/minecraftforge/fml/ModList.class") != null,
         "Forge's raw JUnit bootstrap cannot initialize vanilla registries; the shared generator is tested on Fabric 1.20.1"
      );
      // NeoForge's JUnit runner has a separate loader from the transformed mod.
      // Discover biome providers through the same loader as the fixture classes.
      Thread.currentThread().setContextClassLoader(LodBiomeSnowTest.class.getClassLoader());
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      biomes = com.yucareux.tellus.test.MinecraftTestRegistries.create().lookupOrThrow(Registries.BIOME);
      System.setProperty("tellus.gameDir", gameDirectory.toString());
      System.setProperty("tellus.configDir", gameDirectory.resolve("config").toString());

      // Plain JUnit does not load data-pack tags. Bind the tags used by these
      // surface fixtures and restore the global block registry afterward.
      tagsField = Holder.Reference.class.getDeclaredField("tags");
      tagsField.setAccessible(true);
      Method bindTags = Holder.Reference.class.getDeclaredMethod("bindTags", Collection.class);
      bindTags.setAccessible(true);
      for (Holder.Reference<Biome> biome : biomes.listElements().toList()) {
         bindTags.invoke(biome, biome.is(Biomes.TAIGA) || biome.is(Biomes.SNOWY_TAIGA) ? List.of(BiomeTags.IS_TAIGA) : List.of());
      }
      Set<Block> dirt = Set.of(Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.MYCELIUM);
      for (Block block : BuiltInRegistries.BLOCK) {
         Holder.Reference<Block> holder = block.builtInRegistryHolder();
         ORIGINAL_BLOCK_TAGS.put(holder, tagsField.get(holder));
         bindTags.invoke(holder, dirt.contains(block) ? List.of(BlockTags.DIRT) : List.of());
      }
   }

   @AfterAll
   static void restoreTestEnvironment() throws Exception {
      Thread.currentThread().setContextClassLoader(previousContextClassLoader);
      restoreProperty("tellus.gameDir", previousGameDir);
      restoreProperty("tellus.configDir", previousConfigDir);
      for (Map.Entry<Holder.Reference<Block>, Object> entry : ORIGINAL_BLOCK_TAGS.entrySet()) {
         tagsField.set(entry.getKey(), entry.getValue());
      }
   }

   @Test
   void snowyForestsRequireSnowCoverDataInBothLodPaths() {
      assertNoSnowInBothPaths(Biomes.SNOWY_TAIGA, MountainSurfaceRules.ESA_TREE_COVER);
      assertNoSnowInBothPaths(Biomes.GROVE, MountainSurfaceRules.ESA_TREE_COVER);
   }

   @Test
   void snowyBiomesDoNotAddSnowToOtherLandCoverOrMissingData() {
      for (ResourceKey<Biome> biome : List.of(
         Biomes.SNOWY_PLAINS, Biomes.SNOWY_TAIGA, Biomes.SNOWY_SLOPES,
         Biomes.GROVE, Biomes.ICE_SPIKES, Biomes.FROZEN_PEAKS, Biomes.SNOWY_BEACH
      )) {
         for (int coverClass : new int[]{0, 10, 20, 30, 40, 50, 60, 90, 100}) {
            assertNoSnowInBothPaths(biome, coverClass);
         }
      }
   }

   @Test
   void snowyBeachKeepsSandWithoutSnowCoverData() {
      for (boolean ultraFast : List.of(false, true)) {
         assertEquals(Blocks.SAND.defaultBlockState(), resolve(
            Biomes.SNOWY_BEACH, MountainSurfaceRules.ESA_GRASSLAND, 64, 0, false, ultraFast
         ).top());
      }
   }

   @Test
   void drySnowyTerrainBelowSeaLevelRequiresSnowCoverData() {
      for (boolean ultraFast : List.of(false, true)) {
         assertEquals(Blocks.SAND.defaultBlockState(), resolve(
            Biomes.SNOWY_BEACH, MountainSurfaceRules.ESA_GRASSLAND, 20, 0, false, ultraFast
         ).top());
      }
   }

   @Test
   void submergedSnowyBiomeRetainsItsUnderwaterSurface() {
      for (boolean ultraFast : List.of(false, true)) {
         EarthChunkGenerator.LodSurface surface = resolve(
            Biomes.SNOWY_BEACH, MountainSurfaceRules.ESA_GRASSLAND, 60, 0, true, ultraFast
         );
         assertEquals(Blocks.SAND.defaultBlockState(), surface.top());
         assertEquals(Blocks.SAND.defaultBlockState(), surface.filler());
      }
   }

   @Test
   void temperateForestDoesNotAcquireBiomeSnow() {
      for (boolean ultraFast : List.of(false, true)) {
         EarthChunkGenerator.LodSurface surface = resolve(
            Biomes.TAIGA, MountainSurfaceRules.ESA_TREE_COVER, 96, 0, false, ultraFast
         );
         assertFalse(surface.top().is(Blocks.SNOW_BLOCK));
         assertFalse(surface.top().is(Blocks.POWDER_SNOW));
      }
   }

   @Test
   void temperateMountainForestDoesNotGainSnowBesideASnowFreeSettlement() {
      // The bundled RESOLVE lookup places this point in the Colorado Rockies
      // forests. Supply one climate to both land covers to isolate the reported
      // transition from a snow-free town to a permanently snowy forest.
      ResourceKey<Biome> town = classifyAt(-106.0384, 39.4817, "Dfc", MountainSurfaceRules.ESA_BUILT);
      ResourceKey<Biome> forest = classifyAt(-106.0384, 39.4817, "Dfc", MountainSurfaceRules.ESA_TREE_COVER);
      assertEquals(Biomes.PLAINS, town);
      assertEquals(usesLegacyBiomeClassification() ? Biomes.OLD_GROWTH_PINE_TAIGA : Biomes.TAIGA, forest);
      assertNoSnowInBothPaths(forest, MountainSurfaceRules.ESA_TREE_COVER);
   }

   @Test
   void warmMontaneForestDoesNotGainSnowInEitherLodPath() {
      // Ethiopian montane grasslands and woodlands, with a temperate highland
      // climate: regional mountain vegetation alone is not a snow source.
      ResourceKey<Biome> forest = classifyAt(39.05, 9.6, "Cwb", MountainSurfaceRules.ESA_TREE_COVER);
      assertEquals(usesLegacyBiomeClassification() ? Biomes.BIRCH_FOREST : Biomes.WINDSWEPT_FOREST, forest);
      assertNoSnowInBothPaths(forest, MountainSurfaceRules.ESA_TREE_COVER);
   }

   @Test
   void coldBorealForestKeepsItsBiomeWithoutInventingSnowCover() {
      ResourceKey<Biome> forest = classifyAt(-147.72, 64.84, "Dfc", MountainSurfaceRules.ESA_TREE_COVER);
      assertEquals(usesLegacyBiomeClassification() ? Biomes.OLD_GROWTH_PINE_TAIGA : Biomes.SNOWY_TAIGA, forest);
      assertNoSnowInBothPaths(forest, MountainSurfaceRules.ESA_TREE_COVER);
   }

   @Test
   void mappedSnowSurvivesInColdAndTemperateBiomes() {
      for (ResourceKey<Biome> biome : List.of(Biomes.SNOWY_TAIGA, Biomes.SNOWY_PLAINS, Biomes.GROVE, Biomes.TAIGA)) {
         assertSnowInBothPaths(biome, MountainSurfaceRules.ESA_SNOW_ICE, 320, 0);
      }
   }

   @Test
   void climateBasedBuiltUpTerrainDoesNotInventSnow() {
      EarthGeneratorSettings settings = EarthGeneratorSettings.CODEC.parse(
         JsonOps.INSTANCE, JsonParser.parseString("{\"climate_based_built_up_terrain\":true}")
      ).result().orElseThrow();
      assertTrue(settings.climateBasedBuiltUpTerrain());
      for (ResourceKey<Biome> biome : List.of(Biomes.SNOWY_PLAINS, Biomes.SNOWY_TAIGA, Biomes.GROVE, Biomes.FROZEN_PEAKS)) {
         for (boolean ultraFast : List.of(false, true)) {
            assertSnowFree(resolve(biome, MountainSurfaceRules.ESA_BUILT, 96, 0, false, ultraFast, settings).top());
         }
      }
   }

   @Test
   void nearbySnowCannotSnowOverOtherTerrainButTheBlendedSnowBorderCan() {
      try (@SuppressWarnings("unused") ManagedTerrainNetworkPolicy.Scope ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         Holder<Biome> biome = biomes.getOrThrow(Biomes.SNOWY_TAIGA);
         EarthChunkGenerator generator = new EarthChunkGenerator(new FixedBiomeSource(biome), EarthGeneratorSettings.DEFAULT);
         generator.setLodShorelineOverrideSuppressed(true);
         generator.setLodMountainTransitionCache(new SnowSourceCache(true));
         for (int coverClass : new int[]{0, 10, 20, 30, 40, 50, 60, 90, 100}) {
            for (boolean ultraFast : List.of(false, true)) {
               for (int x = -16; x <= 16; x++) {
                  assertSnowFree(resolveAt(generator, biome, x, 0, 320, false, coverClass, coverClass, 8, false, ultraFast).top());
               }
               // Chunks bend the snow edge with the land-cover border; LODs must too, except over missing data.
               BlockState bordered = resolveAt(
                  generator, biome, 0, 0, 320, false, coverClass, MountainSurfaceRules.ESA_SNOW_ICE, 0, false, ultraFast
               ).top();
               assertEquals(coverClass != MountainSurfaceRules.ESA_NO_DATA, bordered.is(Blocks.SNOW_BLOCK), "cover " + coverClass);
            }
         }
      }
   }

   @Test
   void fullChunkSurfacePalettesRequireSnowDataInFastAndDetailedModes() throws Exception {
      Method selectPalette = Arrays.stream(EarthChunkGenerator.class.getDeclaredMethods())
         .filter(method -> method.getName().equals("selectFullChunkSurfacePalette")).findFirst().orElseThrow();
      selectPalette.setAccessible(true);
      var profilerConstructor = selectPalette.getParameterTypes()[9].getDeclaredConstructor();
      profilerConstructor.setAccessible(true);
      try (@SuppressWarnings("unused") ManagedTerrainNetworkPolicy.Scope ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         for (ResourceKey<Biome> biomeKey : List.of(Biomes.SNOWY_TAIGA, Biomes.GROVE, Biomes.SNOWY_PLAINS)) {
            Holder<Biome> biome = biomes.getOrThrow(biomeKey);
            EarthChunkGenerator generator = new EarthChunkGenerator(new FixedBiomeSource(biome), EarthGeneratorSettings.DEFAULT);
            generator.setLodShorelineOverrideSuppressed(true);
            generator.setLodMountainTransitionCache(new SnowSourceCache(true));
            for (int coverClass : new int[]{0, 10, 30, 50, 60, 70}) {
               for (boolean fast : List.of(false, true)) {
                  Object palette = selectPalette.invoke(
                     generator, biome, 0, 0, 320, false, 0, 0, coverClass, null, profilerConstructor.newInstance(), fast
                  );
                  Method topAccessor = palette.getClass().getDeclaredMethod("top");
                  topAccessor.setAccessible(true);
                  BlockState top = (BlockState)topAccessor.invoke(palette);
                  if (coverClass == MountainSurfaceRules.ESA_SNOW_ICE) {
                     assertEquals(Blocks.SNOW_BLOCK.defaultBlockState(), top);
                  } else {
                     assertSnowFree(top);
                  }
               }
            }
         }
      }
   }

   @Test
   void fullChunksAndBothLodPathsShareSlopeAndLandformRules() throws Exception {
      Method selectPalette = Arrays.stream(EarthChunkGenerator.class.getDeclaredMethods())
         .filter(method -> method.getName().equals("selectFullChunkSurfacePalette")).findFirst().orElseThrow();
      selectPalette.setAccessible(true);
      var profilerConstructor = selectPalette.getParameterTypes()[9].getDeclaredConstructor();
      profilerConstructor.setAccessible(true);
      Set<Block> scree = Set.of(Blocks.GRAVEL, Blocks.ANDESITE, Blocks.DIORITE, Blocks.COBBLESTONE);
      try (@SuppressWarnings("unused") ManagedTerrainNetworkPolicy.Scope ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         Holder<Biome> biome = biomes.getOrThrow(Biomes.MEADOW);
         EarthChunkGenerator generator = new EarthChunkGenerator(new FixedBiomeSource(biome), EarthGeneratorSettings.DEFAULT);
         generator.setLodShorelineOverrideSuppressed(true);
         generator.setLodMountainTransitionCache(new SnowSourceCache(false));
         int steepTurf = 0;
         int outcrops = 0;
         int screeColumns = 0;
         for (int[] shape : new int[][]{{2, 1}, {3, 0}, {4, 0}, {4, 1}, {6, -1}}) {
            for (int coverClass : new int[]{MountainSurfaceRules.ESA_GRASSLAND, MountainSurfaceRules.ESA_BARE}) {
               for (int sample = 0; sample < 24; sample++) {
                  int x = sample * 13;
                  int z = sample * 29 % 211;
                  Object palette = selectPalette.invoke(
                     generator, biome, x, z, HIGHLAND_SURFACE_Y, false, shape[0], shape[1], coverClass, null, profilerConstructor.newInstance(), true
                  );
                  Method topAccessor = palette.getClass().getDeclaredMethod("top");
                  topAccessor.setAccessible(true);
                  Method strataAccessor = palette.getClass().getDeclaredMethod("strata");
                  strataAccessor.setAccessible(true);
                  boolean strata = (boolean)strataAccessor.invoke(palette);
                  // A bare-rock palette only holds a placeholder top; the chunk writes the column's rock at that height.
                  BlockState fullChunkTop = strata
                     ? EarthChunkGenerator.resolveStrataBlock(generator.resolveStrataColumn(x, z, biome), HIGHLAND_SURFACE_Y)
                     : (BlockState)topAccessor.invoke(palette);
                  BlockState lodTop = generator.resolveLodSurface(
                     biome, x, z, HIGHLAND_SURFACE_Y, false, coverClass, coverClass, shape[0], shape[1], null, null, OsmQueryMode.NON_BLOCKING
                  ).top();
                  BlockState ultraFastTop = generator.resolveUltraFastLodSurface(
                     biome, x, z, HIGHLAND_SURFACE_Y, false, coverClass, coverClass, shape[0], shape[1], false, OsmQueryMode.NON_BLOCKING, false
                  ).top();
                  String where = "cover " + coverClass + ", slope " + shape[0] + ", convexity " + shape[1] + " at " + x + "," + z;
                  assertEquals(fullChunkTop, lodTop, where);
                  assertEquals(fullChunkTop, ultraFastTop, where);
                  if (coverClass == MountainSurfaceRules.ESA_GRASSLAND && shape[0] >= 3) {
                     if (strata) outcrops++; else steepTurf++;
                  } else if (coverClass == MountainSurfaceRules.ESA_BARE && shape[0] == 2 && shape[1] == 1) {
                     assertTrue(scree.contains(fullChunkTop.getBlock()), "Scree below steeper rock is loose debris: " + where);
                     screeColumns++;
                  }
               }
            }
         }
         assertTrue(steepTurf > 0 && outcrops > 0, "Steep turf keeps grass between rock outcrops");
         assertTrue(screeColumns > 0);
      }
   }

   @Test
   void deepSnowGateForFullChunksAndRefinementRequiresALocalSource() throws Exception {
      Method snowGate = EarthChunkGenerator.class.getDeclaredMethod("shouldRetainSurfaceSnow", int.class, int.class, int.class);
      snowGate.setAccessible(true);
      try (@SuppressWarnings("unused") ManagedTerrainNetworkPolicy.Scope ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         Holder<Biome> biome = biomes.getOrThrow(Biomes.SNOWY_TAIGA);
         EarthChunkGenerator generator = new EarthChunkGenerator(new FixedBiomeSource(biome), EarthGeneratorSettings.DEFAULT);
         generator.setLodShorelineOverrideSuppressed(true);
         generator.setLodMountainTransitionCache(new SnowSourceCache(true));
         for (int x = -16; x <= 16; x++) {
            for (int coverClass : new int[]{0, 10, 20, 30, 40, 50, 60, 90, 100}) {
               assertFalse((boolean)snowGate.invoke(generator, coverClass, x, 0));
            }
            assertTrue((boolean)snowGate.invoke(generator, MountainSurfaceRules.ESA_SNOW_ICE, x, 0));
         }
         int antarcticZ = (int)Math.round(EarthProjection.latToBlockZ(-75.0, generator.settings().worldScale()));
         assertTrue((boolean)snowGate.invoke(generator, MountainSurfaceRules.ESA_NO_DATA, 0, antarcticZ));
         for (boolean ultraFast : List.of(false, true)) {
            assertEquals(Blocks.SNOW_BLOCK.defaultBlockState(), resolveAt(
               generator, biome, 0, antarcticZ, 320, false, 0, 0, 0, true, ultraFast
            ).top());
         }
      }
   }

   @Test
   void explicitMountainSnowCoverStillClassifiesAsFrozenPeaks() {
      ResourceKey<Biome> snow = classifyAt(39.05, 9.6, "Cwb", MountainSurfaceRules.ESA_SNOW_ICE);
      assertEquals(Biomes.FROZEN_PEAKS, snow);
      assertSnowInBothPaths(snow, MountainSurfaceRules.ESA_SNOW_ICE, 320, 0);
   }

   @Test
   void steepFrozenPeaksKeepDeepslateExposedInBothLodPaths() throws Exception {
      try (
         @SuppressWarnings("unused") ManagedTerrainNetworkPolicy.Scope ignored = ManagedTerrainNetworkPolicy.cacheOnly();
         @SuppressWarnings("unused") TerrainSlopeFixture terrain = new TerrainSlopeFixture(TellusWorldgenSources.elevation(), 30.0, 70.0)
      ) {
         Holder<Biome> biome = biomes.getOrThrow(Biomes.FROZEN_PEAKS);
         EarthChunkGenerator generator = new EarthChunkGenerator(new FixedBiomeSource(biome), EarthGeneratorSettings.DEFAULT);
         generator.setLodShorelineOverrideSuppressed(true);
         assertTrue(generator.sampleDemSlopeDegrees(8, 8) > 65.0, "Fixture must exercise exposed-cliff rules");
         for (boolean ultraFast : List.of(false, true)) {
            EarthChunkGenerator.LodSurface surface = resolveSlope(generator, biome, 8, 8, ultraFast);
            assertTrue(surface.strata(), "Steep mapped snow must expose rock");
            assertFalse(surface.top().is(Blocks.SNOW_BLOCK));
         }
      }
   }

   @Test
   void partialSnowCoverageInFrozenPeaksMatchesThePreviewSlopeMask() throws Exception {
      try (
         @SuppressWarnings("unused") ManagedTerrainNetworkPolicy.Scope ignored = ManagedTerrainNetworkPolicy.cacheOnly();
         @SuppressWarnings("unused") TerrainSlopeFixture terrain = new TerrainSlopeFixture(TellusWorldgenSources.elevation(), 30.0, 52.0)
      ) {
         Holder<Biome> biome = biomes.getOrThrow(Biomes.FROZEN_PEAKS);
         EarthChunkGenerator generator = new EarthChunkGenerator(new FixedBiomeSource(biome), EarthGeneratorSettings.DEFAULT);
         generator.setLodShorelineOverrideSuppressed(true);
         int snowColumns = 0;
         int rockColumns = 0;
         // Partial coverage forms patches tens of blocks wide, so spread the samples
         // across the planar fixture to meet both retained snow and exposed rock.
         for (int row = 0; row < 8; row++) {
            for (int column = 0; column < 8; column++) {
               int x = column * 23;
               int z = row * 19;
               double slope = generator.sampleDemSlopeDegrees(x, z);
               assertEquals(40, SnowSlopePolicy.coveragePercent(slope));
               boolean snow = SnowSlopePolicy.shouldCover(x, z, slope);
               if (snow) snowColumns++; else rockColumns++;
               for (boolean ultraFast : List.of(false, true)) {
                  EarthChunkGenerator.LodSurface surface = resolveSlope(generator, biome, x, z, ultraFast);
                  assertEquals(snow, surface.top().is(Blocks.SNOW_BLOCK), "Slope mask at " + x + "," + z + ", ultraFast=" + ultraFast);
                  if (snow) {
                     assertEquals(Blocks.SNOW_BLOCK.defaultBlockState(), surface.filler());
                  } else if (DeepslateSlopePolicy.shouldCover(x, z, slope)) {
                     assertTrue(surface.strata(), "Exposed rock at " + x + "," + z + ", ultraFast=" + ultraFast);
                  }
               }
            }
         }
         assertTrue(snowColumns > 0 && rockColumns > 0, "Fixture must include both retained snow and exposed rock");
      }
   }

   @Test
   void gentleFrozenPeaksRemainSnowCovered() throws Exception {
      try (
         @SuppressWarnings("unused") ManagedTerrainNetworkPolicy.Scope ignored = ManagedTerrainNetworkPolicy.cacheOnly();
         @SuppressWarnings("unused") TerrainSlopeFixture terrain = new TerrainSlopeFixture(TellusWorldgenSources.elevation(), 30.0, 30.0)
      ) {
         Holder<Biome> biome = biomes.getOrThrow(Biomes.FROZEN_PEAKS);
         EarthChunkGenerator generator = new EarthChunkGenerator(new FixedBiomeSource(biome), EarthGeneratorSettings.DEFAULT);
         generator.setLodShorelineOverrideSuppressed(true);
         assertTrue(generator.sampleDemSlopeDegrees(8, 8) < 45.0);
         for (boolean ultraFast : List.of(false, true)) {
            assertEquals(Blocks.SNOW_BLOCK.defaultBlockState(), resolveSlope(generator, biome, 8, 8, ultraFast).top());
         }
      }
   }

   @Test
   void shadedFacesKeepTheSnowThatSunnyFacesShedInChunksAndLods() throws Exception {
      Method snowGate = EarthChunkGenerator.class.getDeclaredMethod("shouldRetainSurfaceSnow", int.class, int.class, int.class);
      snowGate.setAccessible(true);
      double scale = EarthGeneratorSettings.DEFAULT.worldScale();
      double lodResolution = scale * 32;
      int centerX = (int)Math.floor(EarthProjection.longitudeToBlockX(7.75, scale));
      int centerZ = (int)Math.floor(EarthProjection.latToBlockZ(46.0, scale));
      Holder<Biome> biome = biomes.getOrThrow(Biomes.FROZEN_PEAKS);
      TiltedTerrainFixture.Falls[] faces = {TiltedTerrainFixture.Falls.NORTH, TiltedTerrainFixture.Falls.SOUTH};
      int samples = 64;
      int[] chunkSnow = new int[faces.length];
      int[] lodSnow = new int[faces.length];
      for (int face = 0; face < faces.length; face++) {
         try (
            @SuppressWarnings("unused") ManagedTerrainNetworkPolicy.Scope ignored = ManagedTerrainNetworkPolicy.cacheOnly();
            @SuppressWarnings("unused") TiltedTerrainFixture terrain = new TiltedTerrainFixture(
               TellusWorldgenSources.elevation(), scale, 46.0, 7.75, 52.0, faces[face], lodResolution
            )
         ) {
            EarthChunkGenerator generator = new EarthChunkGenerator(new FixedBiomeSource(biome), EarthGeneratorSettings.DEFAULT);
            generator.setLodShorelineOverrideSuppressed(true);
            generator.setLodMountainTransitionCache(new SnowSourceCache(true));
            for (int i = 0; i < samples; i++) {
               int x = centerX + i % 8 * 23 - 80;
               int z = centerZ + i / 8 * 19 - 70;
               if ((boolean)snowGate.invoke(generator, MountainSurfaceRules.ESA_SNOW_ICE, x, z)) {
                  chunkSnow[face]++;
               }
               generator.setLodSlopeResolution(lodResolution);
               try {
                  if ((boolean)snowGate.invoke(generator, MountainSurfaceRules.ESA_SNOW_ICE, x, z)) {
                     lodSnow[face]++;
                  }
               } finally {
                  generator.clearLodSlopeResolution();
               }
            }
         }
      }
      // At 46 degrees north a 52-degree face holds all its snow facing north and none facing south;
      // facing east or west it would keep 40%.
      assertEquals(samples, chunkSnow[0], "north face in chunks");
      assertEquals(0, chunkSnow[1], "south face in chunks");
      assertTrue(lodSnow[0] > lodSnow[1] + samples / 4, "LOD north " + lodSnow[0] + " vs south " + lodSnow[1]);
   }

   @Test
   void weatherSnowFillsTheLodColumnButKeepsSlopeDeepslate() {
      for (Block filler : List.of(Blocks.STONE, Blocks.DIRT, Blocks.SAND, Blocks.TERRACOTTA)) {
         assertEquals(Blocks.SNOW_BLOCK.defaultBlockState(), EarthChunkGenerator.resolveLodSnowFillerBlock(filler.defaultBlockState()));
      }
      assertEquals(Blocks.DEEPSLATE.defaultBlockState(), EarthChunkGenerator.resolveLodSnowFillerBlock(Blocks.DEEPSLATE.defaultBlockState()));
   }

   private static EarthChunkGenerator.LodSurface resolveSlope(
      EarthChunkGenerator generator, Holder<Biome> biome, int worldX, int worldZ, boolean ultraFast
   ) {
      generator.setLodMountainTransitionCache(new SnowSourceCache(true));
      if (ultraFast) {
         return generator.resolveUltraFastLodSurface(
            biome, worldX, worldZ, 220, false, MountainSurfaceRules.ESA_SNOW_ICE, MountainSurfaceRules.ESA_SNOW_ICE,
            8, 0, false, OsmQueryMode.NON_BLOCKING, false
         );
      }
      return generator.resolveLodSurface(
         biome, worldX, worldZ, 220, false, MountainSurfaceRules.ESA_SNOW_ICE, MountainSurfaceRules.ESA_SNOW_ICE,
         8, 0, null, null, OsmQueryMode.NON_BLOCKING
      );
   }

   @Test
   void asksOnlyForBiomesThisVersionRegisters() {
      // A new world's registration lookup turns each missing biome asked for into an unbound holder that stops world creation.
      Set<Object> missing = new HashSet<>();
      @SuppressWarnings("unchecked")
      HolderGetter<Biome> recording = (HolderGetter<Biome>)Proxy.newProxyInstance(
         LodBiomeSnowTest.class.getClassLoader(),
         new Class<?>[]{HolderGetter.class},
         (proxy, method, args) -> {
            Object result = method.invoke(biomes, args);
            if (result instanceof Optional<?> found && found.isEmpty()) {
               missing.add(args[0]);
            }
            return result;
         }
      );
      try (@SuppressWarnings("unused") ManagedTerrainNetworkPolicy.Scope ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         new EarthBiomeSource(recording, EarthGeneratorSettings.DEFAULT);
      }
      assertEquals(Set.of(), missing);
   }

   private static ResourceKey<Biome> classifyAt(double longitude, double latitude, String climate, int coverClass) {
      try (@SuppressWarnings("unused") ManagedTerrainNetworkPolicy.Scope ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         EarthGeneratorSettings settings = EarthGeneratorSettings.DEFAULT;
         int blockX = (int)Math.round(longitude * EarthProjection.blocksPerDegree(settings.worldScale()));
         int blockZ = (int)Math.round(EarthProjection.latToBlockZ(latitude, settings.worldScale()));
         EarthBiomeSource source = new EarthBiomeSource(biomes, settings);
         WaterSurfaceResolver.WaterColumnData dryLand = new WaterSurfaceResolver.WaterColumnData(false, false, 320, 320);
         return source.getBiomeAtBlock(blockX, blockZ, coverClass, coverClass, dryLand, climate).unwrapKey().orElseThrow();
      }
   }

   private static boolean usesLegacyBiomeClassification() {
      // 1.20.1's biome source uses only ESA + Köppen, without RESOLVE overrides.
      return "1.20.1".equals(MinecraftRelease.VERSION);
   }

   private static void assertNoSnowInBothPaths(ResourceKey<Biome> biome, int coverClass) {
      for (boolean ultraFast : List.of(false, true)) {
         EarthChunkGenerator.LodSurface surface = resolve(biome, coverClass, 320, 0, false, ultraFast);
         assertFalse(surface.top().is(Blocks.SNOW_BLOCK), biome + ", ultraFast=" + ultraFast);
         assertFalse(surface.top().is(Blocks.POWDER_SNOW), biome + ", ultraFast=" + ultraFast);
         assertFalse(surface.filler().is(Blocks.SNOW_BLOCK));
         assertFalse(surface.filler().is(Blocks.POWDER_SNOW));
      }
   }

   private static void assertSnowInBothPaths(ResourceKey<Biome> biome, int coverClass, int surfaceY, int slopeDiff) {
      for (boolean ultraFast : List.of(false, true)) {
         EarthChunkGenerator.LodSurface surface = resolve(biome, coverClass, surfaceY, slopeDiff, false, ultraFast);
         assertEquals(Blocks.SNOW_BLOCK.defaultBlockState(), surface.top(), biome + ", ultraFast=" + ultraFast);
         assertEquals(Blocks.SNOW_BLOCK.defaultBlockState(), surface.filler(), "Distant snowy slopes must not show stone under the snow");
      }
   }

   private static EarthChunkGenerator.LodSurface resolve(
      ResourceKey<Biome> biomeKey, int coverClass, int surfaceY, int slopeDiff, boolean underwater, boolean ultraFast
   ) {
      return resolve(biomeKey, coverClass, surfaceY, slopeDiff, underwater, ultraFast, EarthGeneratorSettings.DEFAULT);
   }

   private static EarthChunkGenerator.LodSurface resolve(
      ResourceKey<Biome> biomeKey, int coverClass, int surfaceY, int slopeDiff, boolean underwater, boolean ultraFast,
      EarthGeneratorSettings settings
   ) {
      // Exercise the actual surface resolvers with supplied terrain inputs. Cache-only
      // mode prevents DEM/OSM fallback reads from requesting live map data.
      try (@SuppressWarnings("unused") ManagedTerrainNetworkPolicy.Scope ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         Holder<Biome> biome = biomes.getOrThrow(biomeKey);
         EarthChunkGenerator generator = new EarthChunkGenerator(new FixedBiomeSource(biome), settings);
         generator.setLodShorelineOverrideSuppressed(true);
         generator.setLodMountainTransitionCache(new SnowSourceCache(coverClass == MountainSurfaceRules.ESA_SNOW_ICE));
         return resolveAt(generator, biome, 0, 0, surfaceY, underwater, coverClass, coverClass, slopeDiff, false, ultraFast);
      }
   }

   private static EarthChunkGenerator.LodSurface resolveAt(
      EarthChunkGenerator generator, Holder<Biome> biome, int x, int z, int surfaceY, boolean underwater,
      int coverClass, int visualCoverClass, int slopeDiff, boolean antarctic, boolean ultraFast
   ) {
      return ultraFast
         ? generator.resolveUltraFastLodSurface(
            biome, x, z, surfaceY, underwater, coverClass, visualCoverClass, slopeDiff, 0, antarctic, OsmQueryMode.NON_BLOCKING, false
         )
         : generator.resolveLodSurface(
            biome, x, z, surfaceY, underwater, coverClass, visualCoverClass, slopeDiff, 0, null, null, OsmQueryMode.NON_BLOCKING
         );
   }

   private static void assertSnowFree(BlockState state) {
      assertFalse(state.is(Blocks.SNOW_BLOCK), state.toString());
      assertFalse(state.is(Blocks.POWDER_SNOW), state.toString());
      assertFalse(state.is(Blocks.SNOW), state.toString());
   }

   private record SnowSourceCache(boolean snow) implements EarthChunkGenerator.LodMountainTransitionCache {
      @Override
      public String mode() {
         return "test";
      }

      @Override
      public double previewResolutionMeters() {
         return EarthGeneratorSettings.DEFAULT.worldScale();
      }

      @Override
      public float sampleVegetationWeight(int worldX, int worldZ, int heightAboveSea) {
         return 0.0F;
      }

      @Override
      public boolean hasSnowSource(int worldX, int worldZ) {
         return this.snow;
      }
   }

   private static void restoreProperty(String name, String previousValue) {
      if (previousValue == null) {
         System.clearProperty(name);
      } else {
         System.setProperty(name, previousValue);
      }
   }
}
