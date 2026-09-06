package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.world.data.osm.OsmQueryMode;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LodBiomeSnowTest {
   @TempDir
   static Path gameDirectory;

   private static HolderLookup<Biome> biomes;
   private static String previousGameDir;
   private static String previousConfigDir;
   private static final Map<Holder.Reference<Block>, Object> ORIGINAL_BLOCK_TAGS = new HashMap<>();
   private static Field tagsField;

   @BeforeAll
   static void bootstrapMinecraft() throws Exception {
      previousGameDir = System.getProperty("tellus.gameDir");
      previousConfigDir = System.getProperty("tellus.configDir");
      assumeFalse(
         LodBiomeSnowTest.class.getClassLoader().getResource("net/minecraftforge/fml/ModList.class") != null,
         "Forge's raw JUnit bootstrap cannot initialize vanilla registries; the shared generator is tested on Fabric 1.20.1"
      );
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      biomes = VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME);
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
      restoreProperty("tellus.gameDir", previousGameDir);
      restoreProperty("tellus.configDir", previousConfigDir);
      for (Map.Entry<Holder.Reference<Block>, Object> entry : ORIGINAL_BLOCK_TAGS.entrySet()) {
         tagsField.set(entry.getKey(), entry.getValue());
      }
   }

   @Test
   void snowyForestsKeepSnowAboveTheirLandCoverPaletteInBothLodPaths() {
      assertSnowInBothPaths(Biomes.SNOWY_TAIGA, MountainSurfaceRules.ESA_TREE_COVER, 96, 0);
      assertSnowInBothPaths(Biomes.GROVE, MountainSurfaceRules.ESA_TREE_COVER, 96, 0);
   }

   @Test
   void snowyBiomesKeepSnowAfterRockAndMeadowSurfaceOverrides() {
      for (ResourceKey<Biome> biome : List.of(
         Biomes.SNOWY_PLAINS, Biomes.SNOWY_TAIGA, Biomes.SNOWY_SLOPES,
         Biomes.GROVE, Biomes.ICE_SPIKES, Biomes.FROZEN_PEAKS
      )) {
         assertSnowInBothPaths(biome, MountainSurfaceRules.ESA_GRASSLAND, 160, 8);
      }
   }

   @Test
   void snowyBeachKeepsSnowAboveSand() {
      assertSnowInBothPaths(Biomes.SNOWY_BEACH, MountainSurfaceRules.ESA_GRASSLAND, 64, 0);
   }

   @Test
   void drySnowyTerrainBelowSeaLevelStillMatchesFullChunks() {
      assertSnowInBothPaths(Biomes.SNOWY_BEACH, MountainSurfaceRules.ESA_GRASSLAND, 20, 0);
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

   private static void assertSnowInBothPaths(ResourceKey<Biome> biome, int coverClass, int surfaceY, int slopeDiff) {
      for (boolean ultraFast : List.of(false, true)) {
         EarthChunkGenerator.LodSurface surface = resolve(biome, coverClass, surfaceY, slopeDiff, false, ultraFast);
         assertEquals(Blocks.SNOW_BLOCK.defaultBlockState(), surface.top(), biome + ", ultraFast=" + ultraFast);
         assertFalse(surface.filler().is(Blocks.SNOW_BLOCK), "Biome snow must not turn the entire terrain column into snow");
         assertFalse(surface.filler().is(Blocks.POWDER_SNOW));
      }
   }

   private static EarthChunkGenerator.LodSurface resolve(
      ResourceKey<Biome> biomeKey, int coverClass, int surfaceY, int slopeDiff, boolean underwater, boolean ultraFast
   ) {
      // Exercise the actual surface resolvers with supplied terrain inputs. Cache-only
      // mode prevents DEM/OSM fallback reads from requesting live map data.
      try (ManagedTerrainNetworkPolicy.Scope ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         Holder<Biome> biome = biomes.getOrThrow(biomeKey);
         EarthChunkGenerator generator = new EarthChunkGenerator(new FixedBiomeSource(biome), EarthGeneratorSettings.DEFAULT);
         generator.setLodShorelineOverrideSuppressed(true);
         if (ultraFast) {
            return generator.resolveUltraFastLodSurface(
               biome, 0, 0, surfaceY, underwater, coverClass, coverClass, slopeDiff, 0, false, OsmQueryMode.NON_BLOCKING, false
            );
         }
         return generator.resolveLodSurface(
            biome, 0, 0, surfaceY, underwater, coverClass, coverClass, slopeDiff, 0, null, null, OsmQueryMode.NON_BLOCKING
         );
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
