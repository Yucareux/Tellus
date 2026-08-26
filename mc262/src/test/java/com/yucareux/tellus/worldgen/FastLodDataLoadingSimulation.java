package com.yucareux.tellus.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.yucareux.tellus.preload.TerrainPreloadPackage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Offline source-loading simulation for the Fast LOD path. This intentionally
 * avoids constructing a Minecraft level or running chunk generation.
 */
public final class FastLodDataLoadingSimulation {
   private static final int DH_DATA_WIDTH = 64;

   private FastLodDataLoadingSimulation() {
   }

   public static void main(String[] args) {
      Options options = Options.parse(args);
      EarthGeneratorSettings settings = experimentalOneToOneSettings();
      int centerX = (int)Math.round(settings.projection().lonToBlockX(options.longitude()));
      int centerZ = (int)Math.round(settings.projection().latToBlockZ(options.latitude()));

      System.out.printf(
         Locale.ROOT,
         "FAST_LOD_SIMULATION profile=experimental-1:1 center=%.5f,%.5f block=%d,%d grid=%d details=%s%n",
         options.latitude(),
         options.longitude(),
         centerX,
         centerZ,
         options.gridWidth(),
         options.details()
      );

      long suiteStart = System.nanoTime();
      for (int detail : options.details()) {
         simulateDetail(settings, centerX, centerZ, options.gridWidth(), detail);
      }
      System.out.printf(Locale.ROOT, "FAST_LOD_SIMULATION_COMPLETE total_ms=%.3f%n", millisSince(suiteStart));
   }

   private static void simulateDetail(EarthGeneratorSettings settings, int centerX, int centerZ, int gridWidth, int detail) {
      int cellSize = 1 << detail;
      long span = (long)gridWidth * cellSize;
      int minX = clampToInt((long)centerX - span / 2L);
      int minZ = clampToInt((long)centerZ - span / 2L);
      int maxX = clampToInt((long)minX + span - 1L);
      int maxZ = clampToInt((long)minZ + span - 1L);
      double previewResolutionMeters = Math.max(settings.worldScale(), cellSize * settings.worldScale());
      boolean osmFeatures = detail <= settings.distantHorizonsOsmRoadMaxDetail();

      long prefetchStart = System.nanoTime();
      try {
         TellusWorldgenSources.prefetchForArea(
            minX,
            minZ,
            maxX,
            maxZ,
            settings,
            osmFeatures,
            osmFeatures,
            previewResolutionMeters
         ).join();
      } catch (CompletionException error) {
         Throwable cause = error.getCause() == null ? error : error.getCause();
         throw new IllegalStateException("Fast LOD source prefetch failed at detail " + detail, cause);
      }
      double prefetchMillis = millisSince(prefetchStart);

      SamplePass cold = samplePass(settings, minX, minZ, gridWidth, cellSize, previewResolutionMeters);
      SamplePass warm = samplePass(settings, minX, minZ, gridWidth, cellSize, previewResolutionMeters);
      if (cold.checksum() != warm.checksum()) {
         throw new IllegalStateException("Fast LOD source samples changed between cold and warm passes at detail " + detail);
      }

      System.out.printf(
         Locale.ROOT,
         "FAST_LOD_DETAIL detail=%d cell_blocks=%d span_chunks=%.1f samples=%d prefetch_ms=%.3f cold_sample_ms=%.3f warm_sample_ms=%.3f "
            + "cold_us_per_sample=%.3f warm_us_per_sample=%.3f min_y=%d max_y=%d checksum=%d%n",
         detail,
         cellSize,
         span / 16.0,
         cold.sampleCount(),
         prefetchMillis,
         cold.elapsedMillis(),
         warm.elapsedMillis(),
         cold.elapsedMillis() * 1000.0 / cold.sampleCount(),
         warm.elapsedMillis() * 1000.0 / warm.sampleCount(),
         cold.minHeight(),
         cold.maxHeight(),
         cold.checksum()
      );
   }

   private static SamplePass samplePass(
      EarthGeneratorSettings settings,
      int minX,
      int minZ,
      int gridWidth,
      int cellSize,
      double previewResolutionMeters
   ) {
      long start = System.nanoTime();
      long checksum = 0L;
      int minHeight = Integer.MAX_VALUE;
      int maxHeight = Integer.MIN_VALUE;
      int offset = cellSize >> 1;
      for (int z = 0; z < gridWidth; z++) {
         int blockZ = minZ + z * cellSize + offset;
         for (int x = 0; x < gridWidth; x++) {
            int blockX = minX + x * cellSize + offset;
            TerrainPreloadPackage.Sample sample = TellusWorldgenSources.samplePreloadTerrain(
               blockX, blockZ, settings, previewResolutionMeters
            );
            minHeight = Math.min(minHeight, sample.terrainHeight());
            maxHeight = Math.max(maxHeight, sample.terrainHeight());
            checksum = checksum * 31L
               + sample.terrainHeight() * 17L
               + sample.coverClass() * 7L
               + (sample.landMaskKnown() ? sample.land() ? 3L : 5L : 11L)
               + (sample.openWatersSelected() ? 13L : 0L)
               + (sample.mapterhornLandOverride() ? 19L : 0L);
         }
      }
      return new SamplePass(
         gridWidth * gridWidth,
         TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - start) / 1000.0,
         minHeight,
         maxHeight,
         checksum
      );
   }

   private static EarthGeneratorSettings experimentalOneToOneSettings() {
      AtomicReference<String> error = new AtomicReference<>();
      JsonElement encoded = EarthGeneratorSettings.CODEC.encodeStart(JsonOps.INSTANCE, EarthGeneratorSettings.DEFAULT)
         .resultOrPartial(error::set)
         .orElseThrow(() -> new IllegalStateException("Unable to encode simulation settings: " + error.get()));
      JsonObject object = encoded.getAsJsonObject();
      object.addProperty("world_scale", 1.0);
      object.addProperty("experimental_increase_height", true);
      object.addProperty("experimental_height_coordinate_profile", HighYPackedCoordinateProfile.PROFILE_ID);
      object.addProperty("distant_horizons_render_mode", EarthGeneratorSettings.DistantHorizonsRenderMode.FAST.id());
      object.addProperty("enable_roads", true);
      object.addProperty("enable_buildings", true);
      object.addProperty("enable_water", true);
      error.set(null);
      return EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, object)
         .resultOrPartial(error::set)
         .orElseThrow(() -> new IllegalStateException("Unable to create experimental 1:1 simulation settings: " + error.get()));
   }

   private static int clampToInt(long value) {
      return (int)Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
   }

   private static double millisSince(long startNanos) {
      return (System.nanoTime() - startNanos) / 1_000_000.0;
   }

   private record SamplePass(int sampleCount, double elapsedMillis, int minHeight, int maxHeight, long checksum) {
   }

   private record Options(double latitude, double longitude, int gridWidth, List<Integer> details) {
      private static Options parse(String[] args) {
         double latitude = 20.6534;
         double longitude = -105.2253;
         int gridWidth = DH_DATA_WIDTH;
         List<Integer> details = new ArrayList<>(List.of(0, 4, 6, 8, 11));
         for (String arg : args) {
            if (arg.startsWith("--latitude=")) {
               latitude = Double.parseDouble(arg.substring("--latitude=".length()));
            } else if (arg.startsWith("--longitude=")) {
               longitude = Double.parseDouble(arg.substring("--longitude=".length()));
            } else if (arg.startsWith("--grid=")) {
               gridWidth = Integer.parseInt(arg.substring("--grid=".length()));
            } else if (arg.startsWith("--details=")) {
               details = parseDetails(arg.substring("--details=".length()));
            } else {
               throw new IllegalArgumentException("Unknown simulation option: " + arg);
            }
         }
         if (gridWidth < 1 || gridWidth > DH_DATA_WIDTH) {
            throw new IllegalArgumentException("Simulation grid must be between 1 and " + DH_DATA_WIDTH);
         }
         return new Options(latitude, longitude, gridWidth, List.copyOf(details));
      }

      private static List<Integer> parseDetails(String value) {
         List<Integer> details = new ArrayList<>();
         for (String part : value.split(",")) {
            int detail = Integer.parseInt(part.trim());
            if (detail < 0 || detail > 24) {
               throw new IllegalArgumentException("Simulation detail must be between 0 and 24");
            }
            details.add(detail);
         }
         if (details.isEmpty()) {
            throw new IllegalArgumentException("At least one simulation detail is required");
         }
         return details;
      }
   }
}
