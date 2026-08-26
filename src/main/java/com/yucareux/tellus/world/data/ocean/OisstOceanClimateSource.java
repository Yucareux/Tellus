package com.yucareux.tellus.world.data.ocean;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.cache.TellusCacheDomain;
import com.yucareux.tellus.cache.TellusCacheFiles;
import com.yucareux.tellus.cache.TellusCacheHandle;
import com.yucareux.tellus.cache.TellusCacheRegistry;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.world.data.source.InputStreamSafety;
import com.yucareux.tellus.worldgen.WorldProjection;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import com.yucareux.tellus.platform.TellusPlatform;
import net.minecraft.util.Mth;

public final class OisstOceanClimateSource implements TellusCacheHandle {
   private static final double GRID_DEGREES = 0.25;
   private static final double MIN_GRID_LAT = -89.875;
   private static final int LAT_CELL_COUNT = 720;
   private static final int LON_CELL_COUNT = 1440;
   private static final String DEFAULT_ENDPOINT =
      "https://www.ncei.noaa.gov/erddap/griddap/ncdc_oisst_v2_avhrr_by_time_zlev_lat_lon";
   private static final String ENDPOINT = System.getProperty("tellus.oisst.endpoint", DEFAULT_ENDPOINT);
   private static final String ENDPOINT_CACHE_ID = Integer.toUnsignedString(ENDPOINT.hashCode(), 16);
   private static final int SAMPLE_YEAR = intProperty("tellus.oisst.sampleYear", 2024);
   private static final int TIME_STRIDE_DAYS = intProperty("tellus.oisst.timeStrideDays", 30);
   private static final int HTTP_CONNECT_TIMEOUT = intProperty("tellus.oisst.connectTimeoutMs", 8000);
   private static final int HTTP_READ_TIMEOUT = intProperty("tellus.oisst.readTimeoutMs", 12000);
   private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
   private static final int MAX_CACHE_CELLS = intProperty("tellus.oisst.cacheCells", 4096);
   private static final String HTTP_USER_AGENT = "Tellus/1.0 (Minecraft Mod)";
   private final Path cacheRoot;
   private final LoadingCache<OisstOceanClimateSource.CellKey, OisstOceanClimateSource.Sample> cache;

   public OisstOceanClimateSource() {
      this.cacheRoot = TellusPlatform.gameDir().resolve("tellus/cache/ocean-oisst-v21");
      this.cache = CacheBuilder.newBuilder()
         .maximumSize(MAX_CACHE_CELLS)
         .build(new CacheLoader<OisstOceanClimateSource.CellKey, OisstOceanClimateSource.Sample>() {
            public OisstOceanClimateSource.Sample load(OisstOceanClimateSource.CellKey key) {
               return OisstOceanClimateSource.this.loadSample(key);
            }
         });
      TellusCacheRegistry.register(this);
   }

   public OisstOceanClimateSource.Sample sample(double blockX, double blockZ, WorldProjection projection) {
      if (projection.worldScale() <= 0.0) {
         return fallbackForLatitude(0.0);
      }

      double lat = projection.blockZToLat(blockZ);
      OisstOceanClimateSource.CellKey key = cellKeyForLatLon(lat, projection.blockXToLon(blockX));
      if (key == null) {
         return fallbackForLatitude(lat);
      }

      if (ManagedTerrainNetworkPolicy.isCacheOnly()) {
         OisstOceanClimateSource.Sample cached = this.cache.getIfPresent(key);
         if (cached == null) {
            cached = this.readCachedSample(key);
            if (cached != null) {
               this.cache.put(key, cached);
            }
         }
         return cached == null ? fallbackForLatitude(lat) : cached;
      }

      try {
         return this.cache.get(key);
      } catch (ExecutionException error) {
         Tellus.LOGGER.debug("Failed to sample NOAA OISST v2.1 cell {}", key, error);
         return fallbackForLatitude(lat);
      }
   }

   public boolean preloadAreaInputs(
      int minBlockX,
      int minBlockZ,
      int maxBlockX,
      int maxBlockZ,
      WorldProjection projection,
      int completedUnits,
      BiConsumer<Integer, String> progress
   ) {
      if (projection.worldScale() <= 0.0) {
         return true;
      }
      int step = Math.max(16, (int)Math.floor(projection.equatorialBlocksPerDegree() * GRID_DEGREES * 0.8));
      Set<OisstOceanClimateSource.CellKey> cells = new LinkedHashSet<>();
      for (long z = minBlockZ; z <= (long)maxBlockZ + step; z += step) {
         double sampleZ = Math.min(maxBlockZ, z);
         double latitude = projection.blockZToLat(sampleZ);
         for (long x = minBlockX; x <= (long)maxBlockX + step; x += step) {
            double sampleX = Math.min(maxBlockX, x);
            OisstOceanClimateSource.CellKey key = cellKeyForLatLon(latitude, projection.blockXToLon(sampleX));
            if (key != null) {
               cells.add(key);
            }
         }
      }

      boolean available = true;
      int finished = 0;
      for (OisstOceanClimateSource.CellKey key : cells) {
         try {
            OisstOceanClimateSource.Sample sample = this.cache.get(key);
            available &= sample.available();
         } catch (ExecutionException | RuntimeException error) {
            available = false;
         }
         finished++;
         if (progress != null) {
            progress.accept(completedUnits + finished, "Ocean climate " + finished + "/" + cells.size());
         }
      }
      return available;
   }

   private OisstOceanClimateSource.Sample loadSample(OisstOceanClimateSource.CellKey key) {
      OisstOceanClimateSource.Sample cached = this.readCachedSample(key);
      if (cached != null) {
         return cached;
      }

      try {
         long generation = TellusCacheRegistry.generation(TellusCacheDomain.OISST);
         OisstOceanClimateSource.Sample downloaded = this.downloadSample(key);
         this.writeCachedSample(key, downloaded, generation);
         return downloaded;
      } catch (IOException | RuntimeException error) {
         Tellus.LOGGER.debug("Falling back from NOAA OISST v2.1 cell {}", key, error);
         return fallbackForLatitude(key.latCenter());
      }
   }

   private OisstOceanClimateSource.Sample readCachedSample(OisstOceanClimateSource.CellKey key) {
      Path path = this.cachePath(key);
      if (!Files.isRegularFile(path)) {
         return null;
      }

      try {
         String[] parts = Files.readString(path, StandardCharsets.UTF_8).trim().split(",", -1);
         if (parts.length < 2) {
            return null;
         }

         double meanSstC = Double.parseDouble(parts[0]);
         double maxIceFraction = Double.parseDouble(parts[1]);
         return Double.isFinite(meanSstC)
            ? new OisstOceanClimateSource.Sample(meanSstC, Mth.clamp(maxIceFraction, 0.0, 1.0), true)
            : null;
      } catch (IOException | NumberFormatException error) {
         Tellus.LOGGER.debug("Failed to read cached NOAA OISST v2.1 cell {}", key, error);
         return null;
      }
   }

   private void writeCachedSample(OisstOceanClimateSource.CellKey key, OisstOceanClimateSource.Sample sample, long generation) {
      if (!sample.available()) {
         return;
      }

      Path path = this.cachePath(key);
      try {
         String value = String.format(Locale.ROOT, "%.4f,%.4f%n", sample.meanSstC(), sample.maxIceFraction());
         TellusCacheFiles.writeStringIfCurrent(TellusCacheDomain.OISST, generation, path, value, StandardCharsets.UTF_8);
      } catch (IOException error) {
         Tellus.LOGGER.debug("Failed to persist NOAA OISST v2.1 cell {}", key, error);
      }
   }

   private OisstOceanClimateSource.Sample downloadSample(OisstOceanClimateSource.CellKey key) throws IOException {
      String queryUrl = this.queryUrl(key);
      HttpURLConnection connection = (HttpURLConnection)URI.create(queryUrl).toURL().openConnection();
      connection.setConnectTimeout(HTTP_CONNECT_TIMEOUT);
      connection.setReadTimeout(HTTP_READ_TIMEOUT);
      connection.setRequestProperty("User-Agent", HTTP_USER_AGENT);
      try {
         int status = connection.getResponseCode();
         if (status < 200 || status >= 300) {
            throw new IOException("NOAA OISST v2.1 returned HTTP " + status);
         }
         long contentLength = connection.getContentLengthLong();
         if (contentLength > MAX_RESPONSE_BYTES) {
            throw new IOException("NOAA OISST v2.1 response exceeds the safety limit");
         }

         byte[] response;
         try (var input = connection.getInputStream()) {
            response = InputStreamSafety.readAllBytes(input, MAX_RESPONSE_BYTES, "NOAA OISST response");
         }
         try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new ByteArrayInputStream(response), StandardCharsets.UTF_8)
         )) {
            return parseCsvSample(reader);
         }
      } finally {
         connection.disconnect();
      }
   }

   private String queryUrl(OisstOceanClimateSource.CellKey key) {
      String endpoint = ENDPOINT.endsWith(".csv") ? ENDPOINT.substring(0, ENDPOINT.length() - 4) : ENDPOINT;
      String start = SAMPLE_YEAR + "-01-15T12:00:00Z";
      String stop = SAMPLE_YEAR + "-12-15T12:00:00Z";
      String lat = String.format(Locale.ROOT, "%.3f", key.latCenter());
      String lon = String.format(Locale.ROOT, "%.3f", key.lonCenter());
      return endpoint
         + ".csv?sst%5B("
         + start
         + "):"
         + TIME_STRIDE_DAYS
         + ":("
         + stop
         + ")%5D%5B(0.0)%5D%5B("
         + lat
         + ")%5D%5B("
         + lon
         + ")%5D,ice%5B("
         + start
         + "):"
         + TIME_STRIDE_DAYS
         + ":("
         + stop
         + ")%5D%5B(0.0)%5D%5B("
         + lat
         + ")%5D%5B("
         + lon
         + ")%5D";
   }

   private static OisstOceanClimateSource.Sample parseCsvSample(BufferedReader reader) throws IOException {
      String headerLine = reader.readLine();
      if (headerLine == null) {
         throw new IOException("NOAA OISST v2.1 response was empty");
      }

      String[] header = splitCsv(headerLine);
      int sstIndex = indexOf(header, "sst");
      int iceIndex = indexOf(header, "ice");
      if (sstIndex < 0) {
         throw new IOException("NOAA OISST v2.1 response missing sst column");
      }

      double sstSum = 0.0;
      int sstCount = 0;
      double maxIceFraction = 0.0;
      String line;
      while ((line = reader.readLine()) != null) {
         if (line.isBlank()) {
            continue;
         }

         String[] parts = splitCsv(line);
         if (sstIndex < parts.length) {
            double sst = parseDouble(parts[sstIndex]);
            if (Double.isFinite(sst)) {
               sstSum += sst;
               sstCount++;
            }
         }

         if (iceIndex >= 0 && iceIndex < parts.length) {
            double ice = normalizeIceFraction(parseDouble(parts[iceIndex]));
            if (Double.isFinite(ice)) {
               maxIceFraction = Math.max(maxIceFraction, ice);
            }
         }
      }

      if (sstCount == 0) {
         throw new IOException("NOAA OISST v2.1 response had no finite sst values");
      }

      return new OisstOceanClimateSource.Sample(sstSum / sstCount, Mth.clamp(maxIceFraction, 0.0, 1.0), true);
   }

   private Path cachePath(OisstOceanClimateSource.CellKey key) {
      String sampleSet = "year-" + SAMPLE_YEAR + "-stride-" + TIME_STRIDE_DAYS;
      return this.cacheRoot
         .resolve(ENDPOINT_CACHE_ID)
         .resolve(sampleSet)
         .resolve(String.format(Locale.ROOT, "%03d", key.latIndex()))
         .resolve(String.format(Locale.ROOT, "%04d.txt", key.lonIndex()));
   }

   private static OisstOceanClimateSource.CellKey cellKeyForLatLon(double lat, double lon) {
      if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
         return null;
      }

      double clampedLat = Mth.clamp(lat, -89.999999, 89.999999);
      double normalizedLon = lon % 360.0;
      if (normalizedLon < 0.0) {
         normalizedLon += 360.0;
      }

      int latIndex = Mth.clamp((int)Math.floor((clampedLat + 90.0) / GRID_DEGREES), 0, LAT_CELL_COUNT - 1);
      int lonIndex = Mth.clamp((int)Math.floor(normalizedLon / GRID_DEGREES), 0, LON_CELL_COUNT - 1);
      double latCenter = MIN_GRID_LAT + latIndex * GRID_DEGREES;
      double lonCenter = 0.125 + lonIndex * GRID_DEGREES;
      return new OisstOceanClimateSource.CellKey(latIndex, lonIndex, latCenter, lonCenter);
   }

   private static OisstOceanClimateSource.Sample fallbackForLatitude(double lat) {
      double absLat = Math.abs(lat);
      if (absLat >= 70.0) {
         return new OisstOceanClimateSource.Sample(-1.8, 0.6, false);
      } else if (absLat >= 55.0) {
         return new OisstOceanClimateSource.Sample(6.0, 0.0, false);
      } else {
         return absLat >= 35.0
            ? new OisstOceanClimateSource.Sample(16.0, 0.0, false)
            : new OisstOceanClimateSource.Sample(26.0, 0.0, false);
      }
   }

   private static String[] splitCsv(String line) {
      return line.split(",", -1);
   }

   private static int indexOf(String[] header, String name) {
      for (int i = 0; i < header.length; i++) {
         if (name.equals(header[i].trim())) {
            return i;
         }
      }

      return -1;
   }

   private static double parseDouble(String value) {
      if (value == null || value.isBlank()) {
         return Double.NaN;
      }

      try {
         return Double.parseDouble(value.trim());
      } catch (NumberFormatException error) {
         return Double.NaN;
      }
   }

   private static double normalizeIceFraction(double value) {
      if (!Double.isFinite(value)) {
         return Double.NaN;
      }

      return value > 1.5 ? value / 100.0 : value;
   }

   private static int intProperty(String key, int defaultValue) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      }

      try {
         return Math.max(1, Integer.parseInt(value));
      } catch (NumberFormatException error) {
         return defaultValue;
      }
   }

   @Override
   public TellusCacheDomain cacheDomain() {
      return TellusCacheDomain.OISST;
   }

   @Override
   public void clearCache() {
      this.cache.invalidateAll();
      this.cache.cleanUp();
   }

   public record Sample(double meanSstC, double maxIceFraction, boolean available) {
   }

   private record CellKey(int latIndex, int lonIndex, double latCenter, double lonCenter) {
   }
}
