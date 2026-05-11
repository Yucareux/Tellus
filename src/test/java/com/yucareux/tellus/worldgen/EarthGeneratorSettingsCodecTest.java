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
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EarthGeneratorSettingsCodecTest {
   @Test
   void roundTripsCurrentSettingsPayload() {
      JsonElement input = JsonParser.parseString(
         """
         {
           "world_scale": 18.5,
           "terrestrial_height_scale": 1.35,
           "oceanic_height_scale": 0.85,
           "height_offset": 48,
           "sea_level": 73,
           "spawn_latitude": 35.6895,
           "spawn_longitude": 139.6917,
           "min_altitude": -80,
           "max_altitude": 420,
           "river_lake_shoreline_blend": 7,
           "ocean_shoreline_blend": 9,
           "shoreline_blend_cliff_limit": false,
           "cave_generation": true,
           "ore_distribution": true,
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
           "enable_water": true
         }
         """
      );

      EarthGeneratorSettings decoded = requireSuccess(EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, input));
      JsonElement encoded = requireSuccess(EarthGeneratorSettings.CODEC.encodeStart(JsonOps.INSTANCE, decoded));
      EarthGeneratorSettings reparsed = requireSuccess(EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, encoded));

      assertEquals(decoded, reparsed);
      JsonObject encodedObject = encoded.getAsJsonObject();
      assertTrue(encodedObject.has("dem_automatic"));
      assertTrue(encodedObject.has("dem_enabled_providers"));
      assertFalse(encodedObject.has("dem_provider"));
      assertEquals("detailed", encodedObject.get("distant_horizons_render_mode").getAsString());
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
      assertTrue(decoded.demSelection().isEnabled(EarthGeneratorSettings.DemProvider.USGS));
      assertTrue(decoded.demSelection().isEnabled(EarthGeneratorSettings.DemProvider.COPERNICUS));
      assertFalse(decoded.demSelection().isEnabled(EarthGeneratorSettings.DemProvider.TERRARIUM));
      assertTrue(decoded.realtimeTime());
      assertTrue(decoded.realtimeWeather());
      assertTrue(decoded.historicalSnow());
      assertTrue(decoded.enableRoads());
      assertTrue(decoded.enableBuildings());
      assertTrue(decoded.enableWater());
      assertTrue(decoded.voxyChunkPregenEnabled());
      assertEquals(192, decoded.voxyChunkPregenMaxRadius());
      assertEquals(10, decoded.voxyChunkPregenChunksPerTick());

      JsonObject encodedObject = requireSuccess(EarthGeneratorSettings.CODEC.encodeStart(JsonOps.INSTANCE, decoded)).getAsJsonObject();
      assertTrue(encodedObject.has("dem_automatic"));
      assertTrue(encodedObject.has("dem_enabled_providers"));
      assertFalse(encodedObject.has("dem_provider"));
   }

   @Test
   void missingOriginDefaultsToSpawn() {
      JsonElement input = JsonParser.parseString(
         """
         {
           "spawn_latitude": 40.7128,
           "spawn_longitude": -74.006,
           "use_local_origin": true
         }
         """
      );

      EarthGeneratorSettings decoded = requireSuccess(EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, input));

      assertTrue(decoded.useLocalOrigin());
      assertEquals(40.7128, decoded.originLatitude());
      assertEquals(-74.006, decoded.originLongitude());
   }

   @Test
   void explicitOriginOverridesSpawn() {
      JsonElement input = JsonParser.parseString(
         """
         {
           "spawn_latitude": 40.7128,
           "spawn_longitude": -74.006,
           "use_local_origin": true,
           "origin_latitude": 51.5074,
           "origin_longitude": -0.1278
         }
         """
      );

      EarthGeneratorSettings decoded = requireSuccess(EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, input));

      assertTrue(decoded.useLocalOrigin());
      assertEquals(51.5074, decoded.originLatitude());
      assertEquals(-0.1278, decoded.originLongitude());
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
