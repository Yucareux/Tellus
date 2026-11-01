package com.yucareux.tellus.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentalHeightBoundsTest {
   @Test
   void oneToOnePolarSettingsStayInsideDensePackedHeightProfile() {
      EarthGeneratorSettings settings = decode(
         """
         {
           "world_scale": 1.0,
           "spawn_latitude": 85.0,
           "experimental_increase_height": true,
           "experimental_height_coordinate_profile": "global_mercator_land_v2"
         }
         """
      );

      EarthGeneratorSettings.HeightLimits limits = EarthGeneratorSettings.resolveHeightLimits(settings);
      int maxY = limits.minY() + limits.height() - 1;

      assertEquals(HighYPackedCoordinateProfile.TELLUS_DIMENSION_MIN_Y, limits.minY());
      assertEquals(HighYPackedCoordinateProfile.TELLUS_DIMENSION_MAX_Y, maxY);
      assertEquals(HighYPackedCoordinateProfile.TELLUS_DIMENSION_Y_SIZE, limits.height());
   }

   @Test
   void automaticBoundsDoNotReserveProjectionCutoffScaleEverywhere() {
      EarthGeneratorSettings settings = decode(
         """
         {
           "world_scale": 30.0,
           "spawn_latitude": 0.0
         }
         """
      );

      EarthGeneratorSettings.HeightLimits limits = EarthGeneratorSettings.resolveHeightLimits(settings);
      int maxY = limits.minY() + limits.height() - 1;

      assertTrue(limits.minY() > -1_000);
      assertTrue(maxY < 1_000);
   }

   @Test
   void disabledAutomaticScalingRebalancesExpandedHeightForDeepOceans() {
      EarthGeneratorSettings settings = decode(
         """
         {
           "world_scale": 1.0,
           "automatic_height_scaling": false,
           "experimental_increase_height": true,
           "experimental_height_coordinate_profile": "global_mercator_land_v2",
           "underground_depth": 512
         }
         """
      );

      int everest = TerrainHeightTransform.blockOffsetAtLatitude(
         8_848.0, 27.9881, 1.0, 1.0, 1.0, true, false
      ) + settings.effectiveHeightOffset();
      int deepestFloor = settings.effectiveHeightOffset()
         - (int)TerrainHeightTransform.experimentalOceanDepthLimit(false);

      assertFalse(settings.automaticHeightScaling());
      assertEquals(EarthGeneratorSettings.EXPERIMENTAL_DEEP_OCEAN_HEIGHT_OFFSET, settings.effectiveHeightOffset());
      assertEquals((int)TerrainHeightTransform.EXPERIMENTAL_LAND_SOFT_CEILING_BLOCKS, everest);
      assertEquals(HighYPackedCoordinateProfile.TELLUS_DIMENSION_MIN_Y, deepestFloor - settings.undergroundDepth());
   }

   @Test
   void automaticBoundsStayAboveDensePackedFloor() {
      // The settings that crashed lighting: automatic altitude resolved to Y -2032.
      EarthGeneratorSettings settings = decode(
         """
         {
           "world_scale": 2.5,
           "height_offset": -1988
         }
         """
      );

      EarthGeneratorSettings.HeightLimits limits = EarthGeneratorSettings.resolveHeightLimits(settings);

      assertTrue(HighYPackedCoordinateProfile.isEnabled());
      assertEquals(HighYPackedCoordinateProfile.TELLUS_DIMENSION_MIN_Y, limits.minY());
      assertEquals(EarthGeneratorSettings.MAX_WORLD_Y, limits.minY() + limits.height() - 1);
   }

   private static EarthGeneratorSettings decode(String json) {
      JsonElement input = JsonParser.parseString(json);
      DataResult<EarthGeneratorSettings> result = EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, input);
      Optional<EarthGeneratorSettings> value = result.resultOrPartial(message -> {
         throw new AssertionError(message);
      });
      assertTrue(value.isPresent());
      return value.get();
   }
}
