package com.yucareux.tellus.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EarthGeneratorSettingsCodecTest {
   @Test
   void defaultSettingsEnableWater() {
      assertTrue(EarthGeneratorSettings.DEFAULT.enableWater());
      assertFalse(EarthGeneratorSettings.DEFAULT.demSelection().automatic());
      assertTrue(EarthGeneratorSettings.DEFAULT.demSelection().terrainTilesEnabled());
      assertEquals(List.of("terrarium"), EarthGeneratorSettings.DEFAULT.demSelection().enabledProviderIds());
      assertFalse(EarthGeneratorSettings.DEFAULT.experimentalIncreaseHeight());
      assertTrue(EarthGeneratorSettings.DEFAULT.automaticHeightScaling());
      assertFalse(EarthGeneratorSettings.DEFAULT.cavesReachSurface());
      assertFalse(EarthGeneratorSettings.DEFAULT.geologicalStonePatches());
      assertEquals(256, EarthGeneratorSettings.DEFAULT.undergroundDepth());
      assertTrue(EarthGeneratorSettings.DEFAULT.usesTerrainShell());
      assertFalse(EarthGeneratorSettings.DEFAULT.suppressesUndergroundGenerationForTerrainShell());
      assertFalse(EarthGeneratorSettings.DEFAULT.tellusManagedTerrainDownloads());
      assertFalse(EarthGeneratorSettings.DEFAULT.showTerrainDownloadOverlay());
      assertFalse(EarthGeneratorSettings.DEFAULT.worldScaleAtSpawn());
      assertFalse(EarthGeneratorSettings.DEFAULT.centerWorldOnSpawn());
      assertFalse(EarthGeneratorSettings.DEFAULT.addStrongholds());
      assertFalse(EarthGeneratorSettings.DEFAULT.addVillages());
      assertFalse(EarthGeneratorSettings.DEFAULT.addMineshafts());
      assertFalse(EarthGeneratorSettings.DEFAULT.addOceanMonuments());
      assertFalse(EarthGeneratorSettings.DEFAULT.addWoodlandMansions());
      assertFalse(EarthGeneratorSettings.DEFAULT.addDesertTemples());
      assertFalse(EarthGeneratorSettings.DEFAULT.addJungleTemples());
      assertFalse(EarthGeneratorSettings.DEFAULT.addPillagerOutposts());
      assertFalse(EarthGeneratorSettings.DEFAULT.addRuinedPortals());
      assertFalse(EarthGeneratorSettings.DEFAULT.addShipwrecks());
      assertFalse(EarthGeneratorSettings.DEFAULT.addOceanRuins());
      assertFalse(EarthGeneratorSettings.DEFAULT.addBuriedTreasure());
      assertFalse(EarthGeneratorSettings.DEFAULT.addIgloos());
      assertFalse(EarthGeneratorSettings.DEFAULT.addWitchHuts());
      assertFalse(EarthGeneratorSettings.DEFAULT.addAncientCities());
      assertFalse(EarthGeneratorSettings.DEFAULT.addTrialChambers());
      assertFalse(EarthGeneratorSettings.DEFAULT.addTrailRuins());
      assertFalse(EarthGeneratorSettings.DEFAULT.deepDark());
      assertFalse(EarthGeneratorSettings.DEFAULT.geodes());
      assertTrue(EarthGeneratorSettings.DEFAULT.distantHorizonsWaterResolver());
   }

   @Test
   void legacyDisabledWaterSettingMigratesToOvertureWater() {
      JsonElement input = JsonParser.parseString("{\"enable_water\":false}");

      EarthGeneratorSettings decoded = requireSuccess(EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, input));
      JsonObject encoded = requireSuccess(EarthGeneratorSettings.CODEC.encodeStart(JsonOps.INSTANCE, decoded)).getAsJsonObject();

      assertTrue(decoded.enableWater());
      assertTrue(encoded.get("enable_water").getAsBoolean());
   }

   @Test
   void legacySeaLevelSettingIsIgnored() {
      JsonElement input = JsonParser.parseString("{\"height_offset\":48,\"sea_level\":73}");

      EarthGeneratorSettings decoded = requireSuccess(EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, input));
      JsonObject encoded = requireSuccess(EarthGeneratorSettings.CODEC.encodeStart(JsonOps.INSTANCE, decoded)).getAsJsonObject();

      assertEquals(48, decoded.effectiveHeightOffset());
      assertFalse(encoded.has("sea_level"));
   }

   @Test
   void experimentalHeightPreservesSelectedWorldScale() {
      JsonElement input = JsonParser.parseString(
         """
         {
           "world_scale": 30.0,
           "experimental_increase_height": true,
           "experimental_height_coordinate_profile": "%s"
         }
         """.formatted(HighYPackedCoordinateProfile.PROFILE_ID)
      );

      EarthGeneratorSettings decoded = requireSuccess(EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, input));

      assertTrue(decoded.experimentalIncreaseHeight());
      assertEquals(30.0, decoded.worldScale());
      assertEquals(decoded.worldScale(), decoded.effectiveVerticalWorldScale());
   }

   @Test
   void acceptsWorldScaleThroughOneThousandAndClampsLargerValues() {
      EarthGeneratorSettings maximum = requireSuccess(
         EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"world_scale\":1000.0}"))
      );
      EarthGeneratorSettings oversized = requireSuccess(
         EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"world_scale\":1001.0}"))
      );

      assertEquals(EarthGeneratorSettings.MAX_WORLD_SCALE, maximum.worldScale());
      assertEquals(EarthGeneratorSettings.MAX_WORLD_SCALE, oversized.worldScale());
   }

   @Test
   void legacyProviderIdsNormalizeToMapterhorn() {
      EarthGeneratorSettings.DemSelection automatic = EarthGeneratorSettings.DemSelection.automaticSelection();
      EarthGeneratorSettings.DemSelection manual = EarthGeneratorSettings.DemSelection.manual(0);
      EarthGeneratorSettings.DemSelection serialized = EarthGeneratorSettings.DemSelection.manual(
         EarthGeneratorSettings.DemSelection.maskFromProviderIds(List.of("terrarium", "gebco2026", "usgs", "inegi", "hma"))
      );

      assertFalse(automatic.automatic());
      assertFalse(manual.automatic());
      assertFalse(serialized.automatic());
      assertTrue(automatic.terrainTilesEnabled());
      assertTrue(manual.terrainTilesEnabled());
      assertTrue(serialized.terrainTilesEnabled());
      assertEquals(List.of("terrarium"), automatic.enabledProviderIds());
      assertEquals(List.of("terrarium"), manual.enabledProviderIds());
      assertEquals(List.of("terrarium"), serialized.enabledProviderIds());
      assertEquals(EarthGeneratorSettings.DemProvider.AUTO, EarthGeneratorSettings.DemProvider.fromId("hma"));
      assertEquals(EarthGeneratorSettings.DemProvider.AUTO, EarthGeneratorSettings.DemProvider.fromId("inegi"));
   }

   @Test
   void roundTripsCurrentSettingsPayload() {
      JsonElement input = JsonParser.parseString(
         """
         {
           "world_scale": 18.5,
           "terrestrial_height_scale": 1.35,
           "oceanic_height_scale": 0.85,
           "height_offset": 48,
           "spawn_latitude": 35.6895,
           "spawn_longitude": 139.6917,
           "min_altitude": -80,
           "max_altitude": 420,
           "river_lake_shoreline_blend": 7,
           "ocean_shoreline_blend": 9,
           "shoreline_blend_cliff_limit": false,
           "cave_generation": true,
           "caves_reach_surface": true,
           "underground_depth": 192,
           "ore_distribution": true,
           "geological_stone_patches": true,
           "lava_pools": true,
           "add_strongholds": false,
           "add_villages": false,
           "add_mineshafts": true,
           "add_ocean_monuments": false,
           "add_woodland_mansions": false,
           "add_desert_temples": false,
           "add_jungle_temples": false,
           "add_pillager_outposts": false,
           "add_ruined_portals": true,
           "add_shipwrecks": false,
           "add_ocean_ruins": false,
           "add_buried_treasure": false,
           "add_igloos": false,
           "add_witch_huts": false,
           "add_ancient_cities": true,
           "add_trial_chambers": true,
           "add_trail_ruins": false,
           "deep_dark": true,
           "geodes": false,
           "distant_horizons_water_resolver": true,
           "distant_horizons_render_mode": "detailed",
           "dem_automatic": false,
           "dem_enabled_providers": ["terrarium", "usgs", "copernicus"],
           "realtime_time": true,
           "realtime_weather": true,
           "historical_snow": true,
           "voxy_chunk_pregen_enabled": true,
           "voxy_chunk_pregen_max_radius": 128,
           "voxy_chunk_pregen_chunks_per_tick": 8,
           "enable_roads": true,
           "enable_buildings": true,
           "enable_water": true,
           "thin_shell_terrain": true,
           "climate_based_built_up_terrain": true,
           "random_biomes": true,
           "automatic_height_scaling": false,
           "tellus_managed_terrain_downloads": false,
           "show_terrain_download_overlay": false,
           "random_biome_density": 0.25,
           "random_biome_seed": 987654321,
           "random_biome_ids": ["desert", "warm_ocean", "lush_caves"]
         }
         """
      );

      EarthGeneratorSettings decoded = requireSuccess(EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, input));
      JsonElement encoded = requireSuccess(EarthGeneratorSettings.CODEC.encodeStart(JsonOps.INSTANCE, decoded));
      EarthGeneratorSettings reparsed = requireSuccess(EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, encoded));

      assertEquals(decoded, reparsed);
      assertFalse(encoded.getAsJsonObject().has("sea_level"));
      assertTrue(decoded.thinShellTerrain());
      assertTrue(decoded.cavesReachSurface());
      assertTrue(decoded.geologicalStonePatches());
      assertEquals(192, decoded.undergroundDepth());
      assertTrue(decoded.climateBasedBuiltUpTerrain());
      assertTrue(decoded.randomBiomes());
      assertFalse(decoded.tellusManagedTerrainDownloads());
      assertFalse(decoded.showTerrainDownloadOverlay());
      assertFalse(decoded.centerWorldOnSpawn());
      assertFalse(decoded.experimentalIncreaseHeight());
      assertFalse(decoded.automaticHeightScaling());
      assertEquals(0.25, decoded.randomBiomeDensity());
      assertEquals(987654321L, decoded.randomBiomeSeed());
      assertEquals(List.of("desert", "warm_ocean", "lush_caves"), decoded.randomBiomeIds());
      assertTrue(reparsed.thinShellTerrain());
      assertTrue(reparsed.cavesReachSurface());
      assertTrue(reparsed.geologicalStonePatches());
      assertEquals(192, reparsed.undergroundDepth());
      assertTrue(reparsed.climateBasedBuiltUpTerrain());
      assertTrue(reparsed.randomBiomes());
      assertFalse(reparsed.experimentalIncreaseHeight());
      assertFalse(reparsed.automaticHeightScaling());
      assertEquals(0.25, reparsed.randomBiomeDensity());
      assertEquals(987654321L, reparsed.randomBiomeSeed());
      assertEquals(decoded.randomBiomeIds(), reparsed.randomBiomeIds());
      assertFalse(decoded.demSelection().automatic());
      assertTrue(decoded.demSelection().terrainTilesEnabled());
      assertEquals(List.of("terrarium"), decoded.demSelection().enabledProviderIds());
      assertEquals(decoded.demSelection(), reparsed.demSelection());
      JsonObject encodedObject = encoded.getAsJsonObject();
      assertTrue(encodedObject.has("dem_automatic"));
      assertTrue(encodedObject.has("dem_enabled_providers"));
      assertTrue(encodedObject.getAsJsonArray("dem_enabled_providers").contains(JsonParser.parseString("\"terrarium\"")));
      assertFalse(encodedObject.getAsJsonArray("dem_enabled_providers").contains(JsonParser.parseString("\"gebco2026\"")));
      assertFalse(encodedObject.has("dem_provider"));
      assertEquals(7, encodedObject.get("river_lake_shoreline_blend").getAsInt());
      assertEquals("detailed", encodedObject.get("distant_horizons_render_mode").getAsString());
      assertTrue(encodedObject.get("thin_shell_terrain").getAsBoolean());
      assertTrue(encodedObject.get("caves_reach_surface").getAsBoolean());
      assertTrue(encodedObject.get("geological_stone_patches").getAsBoolean());
      assertEquals(192, encodedObject.get("underground_depth").getAsInt());
      assertTrue(encodedObject.get("climate_based_built_up_terrain").getAsBoolean());
      assertTrue(encodedObject.get("random_biomes").getAsBoolean());
      assertFalse(encodedObject.get("tellus_managed_terrain_downloads").getAsBoolean());
      assertFalse(encodedObject.get("show_terrain_download_overlay").getAsBoolean());
      assertFalse(encodedObject.get("automatic_height_scaling").getAsBoolean());
      assertEquals(0.25, encodedObject.get("random_biome_density").getAsDouble());
      assertEquals(987654321L, encodedObject.get("random_biome_seed").getAsLong());
      assertEquals(3, encodedObject.getAsJsonArray("random_biome_ids").size());
      if (encodedObject.has("experimental_increase_height")) {
         assertFalse(encodedObject.get("experimental_increase_height").getAsBoolean());
      }
   }

   @Test
   void loadsLegacy12111SettingsFixture() throws IOException {
      JsonElement input = loadFixture("fixtures/earth_generator_settings_legacy_1_21_11.json");
      EarthGeneratorSettings decoded = requireSuccess(EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, input));

      assertEquals(24.0, decoded.worldScale());
      assertEquals(51.5074, decoded.spawnLatitude());
      assertEquals(-0.1278, decoded.spawnLongitude());
      assertEquals(EarthGeneratorSettings.DistantHorizonsRenderMode.DETAILED, decoded.distantHorizonsRenderMode());
      assertFalse(decoded.demSelection().automatic());
      assertTrue(decoded.demSelection().isEnabled(EarthGeneratorSettings.DemProvider.TERRARIUM));
      assertEquals(List.of("terrarium"), decoded.demSelection().enabledProviderIds());
      assertTrue(decoded.realtimeTime());
      assertTrue(decoded.realtimeWeather());
      assertTrue(decoded.historicalSnow());
      assertTrue(decoded.enableRoads());
      assertTrue(decoded.enableBuildings());
      assertTrue(decoded.enableWater());
      assertFalse(decoded.thinShellTerrain());
      assertFalse(decoded.climateBasedBuiltUpTerrain());
      assertFalse(decoded.randomBiomes());
      assertFalse(decoded.experimentalIncreaseHeight());
      assertFalse(decoded.cavesReachSurface());
      assertFalse(decoded.geologicalStonePatches());
      assertFalse(decoded.tellusManagedTerrainDownloads());
      assertFalse(decoded.showTerrainDownloadOverlay());
      assertEquals(EarthGeneratorSettings.DEFAULT_RANDOM_BIOME_DENSITY, decoded.randomBiomeDensity());
      assertEquals(EarthGeneratorSettings.DEFAULT_RANDOM_BIOME_SEED, decoded.randomBiomeSeed());
      assertEquals(EarthGeneratorSettings.DEFAULT.randomBiomeIds(), decoded.randomBiomeIds());
      assertTrue(decoded.voxyChunkPregenEnabled());
      assertEquals(192, decoded.voxyChunkPregenMaxRadius());
      assertEquals(10, decoded.voxyChunkPregenChunksPerTick());
      assertFalse(decoded.worldScaleAtSpawn());

      JsonObject encodedObject = requireSuccess(EarthGeneratorSettings.CODEC.encodeStart(JsonOps.INSTANCE, decoded)).getAsJsonObject();
      assertTrue(encodedObject.has("dem_automatic"));
      assertTrue(encodedObject.has("dem_enabled_providers"));
      assertFalse(encodedObject.has("dem_provider"));
      assertEquals(6, encodedObject.get("river_lake_shoreline_blend").getAsInt());
   }

   @Test
   void worldScaleAtSpawnIsOffByDefaultAndRoundTrips() {
      assertFalse(EarthGeneratorSettings.DEFAULT.worldScaleAtSpawn());
      assertFalse(EarthGeneratorSettings.DEFAULT_WORLD_SCALE_AT_SPAWN);

      JsonObject missingField = requireSuccess(EarthGeneratorSettings.CODEC.encodeStart(JsonOps.INSTANCE, EarthGeneratorSettings.DEFAULT))
         .getAsJsonObject();
      missingField.remove("world_scale_at_spawn");
      EarthGeneratorSettings legacy = requireSuccess(EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, missingField));
      assertFalse(legacy.worldScaleAtSpawn());
      assertEquals(EarthGeneratorSettings.DEFAULT.worldScale(), legacy.worldScale());

      EarthGeneratorSettings enabled = EarthGeneratorSettings.DEFAULT.withWorldScaleAtSpawn(true);
      JsonObject encoded = requireSuccess(EarthGeneratorSettings.CODEC.encodeStart(JsonOps.INSTANCE, enabled)).getAsJsonObject();
      assertTrue(encoded.get("world_scale_at_spawn").getAsBoolean());
      // The stored world scale is always the equatorial value regardless of how the UI presents it.
      assertEquals(EarthGeneratorSettings.DEFAULT.worldScale(), encoded.get("world_scale").getAsDouble());

      EarthGeneratorSettings decoded = requireSuccess(EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, encoded));
      assertTrue(decoded.worldScaleAtSpawn());
      assertEquals(enabled, decoded);
   }

   @Test
   void centerWorldOnSpawnIsOffByDefaultAndRoundTripsWithoutChangingLegacyWorlds() {
      assertFalse(EarthGeneratorSettings.DEFAULT.centerWorldOnSpawn());
      assertFalse(EarthGeneratorSettings.DEFAULT_CENTER_WORLD_ON_SPAWN);

      JsonObject missingField = requireSuccess(EarthGeneratorSettings.CODEC.encodeStart(JsonOps.INSTANCE, EarthGeneratorSettings.DEFAULT))
         .getAsJsonObject();
      missingField.remove("center_world_on_spawn");
      EarthGeneratorSettings legacy = requireSuccess(EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, missingField));
      assertFalse(legacy.centerWorldOnSpawn());
      assertFalse(legacy.projection().isCentered());
      assertEquals(
         legacy.spawnLongitude() * EarthProjection.METERS_PER_DEGREE / legacy.worldScale(),
         legacy.projection().lonToBlockX(legacy.spawnLongitude()),
         1.0E-9
      );

      EarthGeneratorSettings enabled = EarthGeneratorSettings.DEFAULT
         .withSpawn(37.7459, -119.5332)
         .withCenterWorldOnSpawn(true);
      JsonObject encoded = requireSuccess(EarthGeneratorSettings.CODEC.encodeStart(JsonOps.INSTANCE, enabled)).getAsJsonObject();
      assertTrue(encoded.get("center_world_on_spawn").getAsBoolean());
      EarthGeneratorSettings decoded = requireSuccess(EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, encoded));

      assertEquals(enabled, decoded);
      assertTrue(decoded.projection().isCentered());
      assertEquals(0.0, decoded.projection().lonToBlockX(decoded.spawnLongitude()), 1.0E-9);
      assertEquals(0.0, decoded.projection().latToBlockZ(decoded.spawnLatitude()), 1.0E-9);
   }

   @Test
   void worldScalePresentationConvertsBetweenEquatorAndSpawn() {
      double equatorial = 30.0;

      assertEquals(equatorial, EarthGeneratorSettings.groundScaleAtLatitude(equatorial, 0.0), 1.0E-12);
      assertEquals(15.0, EarthGeneratorSettings.groundScaleAtLatitude(equatorial, 60.0), 1.0E-9);
      assertEquals(15.0, EarthGeneratorSettings.groundScaleAtLatitude(equatorial, -60.0), 1.0E-9);

      assertEquals(equatorial, EarthGeneratorSettings.displayedWorldScale(equatorial, 60.0, false), 1.0E-12);
      assertEquals(15.0, EarthGeneratorSettings.displayedWorldScale(equatorial, 60.0, true), 1.0E-9);
      assertEquals(30.0, EarthGeneratorSettings.equatorialWorldScale(15.0, 60.0, true), 1.0E-9);
      assertEquals(15.0, EarthGeneratorSettings.equatorialWorldScale(15.0, 60.0, false), 1.0E-12);

      double[] latitudes = {-84.0, -63.0695, -27.9881, 0.0, 37.7459, 69.6496, 84.0};
      double[] displayed = {1.0, 12.5, 30.0, 250.0};
      for (double latitude : latitudes) {
         for (double value : displayed) {
            double stored = EarthGeneratorSettings.equatorialWorldScale(value, latitude, true);
            assertTrue(stored >= value, "equatorial scale is never finer than the at-spawn scale");
            assertEquals(value, EarthGeneratorSettings.displayedWorldScale(stored, latitude, true), 1.0E-9);
         }
      }

      EarthGeneratorSettings halfDome = EarthGeneratorSettings.DEFAULT.withSpawn(37.7459, -119.5332).withWorldScaleAtSpawn(true);
      assertEquals(EarthGeneratorSettings.DEFAULT.worldScale(), halfDome.worldScale());
      assertEquals(halfDome.worldScale() / EarthProjection.heightScaleCorrectionAtLatitude(37.7459), halfDome.groundScaleAtSpawn(), 1.0E-9);
      assertEquals(halfDome.groundScaleAtSpawn(), halfDome.displayedWorldScale(), 1.0E-12);
      assertEquals(halfDome.worldScale(), halfDome.withWorldScaleAtSpawn(false).displayedWorldScale(), 1.0E-12);
   }

   private static JsonElement loadFixture(String path) throws IOException {
      try (InputStream stream = EarthGeneratorSettingsCodecTest.class.getClassLoader().getResourceAsStream(path)) {
         assertNotNull(stream, "Missing fixture " + path);
         try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
         }
      }
   }

   private static <T> T requireSuccess(DataResult<T> result) {
      Optional<T> value = result.resultOrPartial(message -> {
         throw new AssertionError(message);
      });
      return value.orElseThrow(() -> new AssertionError("Codec operation returned no value"));
   }
}
