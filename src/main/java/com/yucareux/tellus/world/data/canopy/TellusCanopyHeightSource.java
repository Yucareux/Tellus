package com.yucareux.tellus.world.data.canopy;

import com.yucareux.tellus.compat.MinecraftRelease;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.cache.TellusCacheDomain;
import com.yucareux.tellus.cache.TellusCacheFiles;
import com.yucareux.tellus.cache.TellusCacheHandle;
import com.yucareux.tellus.cache.TellusCacheRegistry;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.platform.TellusPlatform;
import com.yucareux.tellus.world.data.source.ParallelDownloadRunner;
import com.yucareux.tellus.worldgen.EarthProjection;
import com.yucareux.tellus.worldgen.WorldProjection;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Samples the ETH Global Canopy Height 2020 layer from its official ArcGIS
 * Living Atlas image-tile cache.
 *
 * <p>Only the small LERC tiles actually encountered by a world are downloaded.
 * The service's overview levels are selected for coarse previews, while
 * full-detail generation keeps native data at the map scales where individual
 * tree placement benefits from it.</p>
 */
public final class TellusCanopyHeightSource implements TellusCacheHandle {
   public static final String DATASET_NAME = "ETH Global Canopy Height 2020";
   public static final String SERVICE_URL =
      "https://tiledimageservices.arcgis.com/P3ePLMYs2RVChkJx/arcgis/rest/services/10m_Tree_Canopy_Height/ImageServer";
   private static final int TILE_SIZE = 256;
   private static final int MIN_LEVEL = 0;
   private static final int NATIVE_LEVEL = 13;
   private static final double NATIVE_RESOLUTION_DEGREES = 1.0 / 12000.0;
   private static final double NATIVE_RESOLUTION_METERS = EarthProjection.METERS_PER_DEGREE * NATIVE_RESOLUTION_DEGREES;
   private static final double ORIGIN_LONGITUDE = -180.0;
   private static final double ORIGIN_LATITUDE = 84.0;
   private static final double MIN_LATITUDE = -60.0;
   private static final double MAX_LATITUDE = 84.0;
   private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
   private static final int MEMORY_TILE_COUNT = intProperty("tellus.canopyHeight.memoryTiles", 64, 4, 1024);
   private static final long DISK_CACHE_BYTES =
      (long)intProperty("tellus.canopyHeight.diskCacheMiB", 256, 16, 4096) * 1024L * 1024L;
   private static final int CONNECT_TIMEOUT_SECONDS = intProperty("tellus.canopyHeight.connectTimeoutSeconds", 10, 1, 120);
   private static final int REQUEST_TIMEOUT_SECONDS = intProperty("tellus.canopyHeight.requestTimeoutSeconds", 30, 1, 180);
   private static final int FETCH_ATTEMPTS = intProperty("tellus.canopyHeight.fetchAttempts", 2, 1, 5);
   private static final int MAX_AREA_TILE_COUNT = intProperty("tellus.canopyHeight.maxAreaTiles", 4096, 64, 65536);
   private static final int DECODER_COUNT = intProperty(
      "tellus.canopyHeight.decodeThreads",
      Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors())),
      1,
      8
   );
   private static final long FAILURE_RETRY_NANOS = TimeUnit.SECONDS.toNanos(30L);

   private final String serviceUrl = configuredServiceUrl();
   private final Path cacheRoot = TellusPlatform.gameDir().resolve("tellus/cache/canopy-height-eth-2020-v1/arcgis-living-atlas");
   private final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .version(HttpClient.Version.HTTP_2)
      .build();
   private final Cache<TileKey, RasterTile> cache = CacheBuilder.newBuilder().maximumSize(MEMORY_TILE_COUNT).build();
   private final Cache<TileKey, Long> failedUntil = CacheBuilder.newBuilder().maximumSize(512).build();
   private final ArrayBlockingQueue<LercU8Decoder> decoders = new ArrayBlockingQueue<>(DECODER_COUNT);
   private final AtomicInteger writesSincePrune = new AtomicInteger();
   private final AtomicBoolean serviceFailureLogged = new AtomicBoolean();

   public TellusCanopyHeightSource() {
      for (int index = 0; index < DECODER_COUNT; index++) {
         this.decoders.add(new LercU8Decoder());
      }
      TellusCacheRegistry.register(this);
   }

   public CanopySample sampleCanopy(double blockX, double blockZ, WorldProjection projection) {
      return this.sampleCanopy(blockX, blockZ, projection, projection.worldScale(), managedLookupMode());
   }

   public CanopySample sampleCanopy(
      double blockX, double blockZ, WorldProjection projection, double previewResolutionMeters
   ) {
      return this.sampleCanopy(blockX, blockZ, projection, previewResolutionMeters, managedLookupMode());
   }

   public CanopySample sampleCanopyLocalOnly(
      double blockX, double blockZ, WorldProjection projection, double previewResolutionMeters
   ) {
      return this.sampleCanopy(blockX, blockZ, projection, previewResolutionMeters, LookupMode.LOCAL_ONLY);
   }

   public CanopySample sampleCanopyMemoryOnly(
      double blockX, double blockZ, WorldProjection projection, double previewResolutionMeters
   ) {
      return this.sampleCanopy(blockX, blockZ, projection, previewResolutionMeters, LookupMode.MEMORY_ONLY);
   }

   private CanopySample sampleCanopy(
      double blockX,
      double blockZ,
      WorldProjection projection,
      double previewResolutionMeters,
      LookupMode lookupMode
   ) {
      GeoPoint point = geoPoint(blockX, blockZ, projection);
      if (point == null || !withinCoverage(point.longitude(), point.latitude())) {
         return CanopySample.unavailable();
      }

      int level = levelForResolution(projection.worldScale(), previewResolutionMeters);
      PixelPosition center = pixelPosition(point.longitude(), point.latitude(), level);
      if (center == null) {
         return CanopySample.unavailable();
      }

      int[] values = new int[9];
      int valueCount = 0;
      int centerHeight = -1;
      for (int dy = -1; dy <= 1; dy++) {
         for (int dx = -1; dx <= 1; dx++) {
            int value = this.sampleGlobalPixel(level, center.globalPixelX() + dx, center.globalPixelY() + dy, lookupMode);
            if (dx == 0 && dy == 0) {
               centerHeight = value;
            }
            if (value >= 0) {
               values[valueCount++] = value;
            }
         }
      }
      if (valueCount == 0) {
         return CanopySample.unavailable();
      }

      java.util.Arrays.sort(values, 0, valueCount);
      double sum = 0.0;
      for (int index = 0; index < valueCount; index++) {
         sum += values[index];
      }
      double median = percentile(values, valueCount, 0.5);
      double percentile75 = percentile(values, valueCount, 0.75);
      double percentile90 = percentile(values, valueCount, 0.9);
      double maximum = values[valueCount - 1];
      double centerValue = centerHeight >= 0 ? centerHeight : median;
      return new CanopySample(true, centerValue, sum / valueCount, median, percentile75, percentile90, maximum, level, valueCount);
   }

   public void prefetchTiles(
      int centerBlockX, int centerBlockZ, WorldProjection projection, int radiusChunks, double previewResolutionMeters
   ) {
      int radiusBlocks = Math.max(8, Math.max(0, radiusChunks) * 16 + 8);
      this.preloadAreaInputs(
         centerBlockX - radiusBlocks,
         centerBlockZ - radiusBlocks,
         centerBlockX + radiusBlocks,
         centerBlockZ + radiusBlocks,
         projection,
         previewResolutionMeters,
         0,
         null
      );
   }

   public int preloadAreaTaskCount(
      int minBlockX,
      int minBlockZ,
      int maxBlockX,
      int maxBlockZ,
      WorldProjection projection,
      double previewResolutionMeters
   ) {
      return tileKeysForArea(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, previewResolutionMeters).size();
   }

   /**
    * Selects the finest available overview that keeps a large, transient preview
    * inside its tile budget. Regular chunk generation never uses this adjustment.
    */
   public static double resolutionForAreaTileBudget(
      int minBlockX,
      int minBlockZ,
      int maxBlockX,
      int maxBlockZ,
      WorldProjection projection,
      double requestedResolutionMeters,
      int maxTileCount
   ) {
      double resolutionMeters = finitePositive(requestedResolutionMeters, projection.worldScale());
      int tileBudget = Math.max(1, maxTileCount);
      for (int overviewStep = 0; overviewStep <= NATIVE_LEVEL + 4; overviewStep++) {
         if (areaTileCount(
               minBlockX,
               minBlockZ,
               maxBlockX,
               maxBlockZ,
               projection,
               resolutionMeters
            ) <= tileBudget) {
            break;
         }
         resolutionMeters *= 2.0;
         if (!Double.isFinite(resolutionMeters)) {
            return Double.MAX_VALUE;
         }
      }
      return resolutionMeters;
   }

   static int areaTileCount(
      int minBlockX,
      int minBlockZ,
      int maxBlockX,
      int maxBlockZ,
      WorldProjection projection,
      double previewResolutionMeters
   ) {
      return tileKeysForArea(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, previewResolutionMeters).size();
   }

   public int preloadAreaInputs(
      int minBlockX,
      int minBlockZ,
      int maxBlockX,
      int maxBlockZ,
      WorldProjection projection,
      double previewResolutionMeters,
      int completedOffset,
      BiConsumer<Integer, String> progress
   ) {
      if (ManagedTerrainNetworkPolicy.isCacheOnly()) {
         return completedOffset;
      }

      List<TileKey> keys = tileKeysForArea(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, previewResolutionMeters);
      if (keys.isEmpty()) {
         return completedOffset;
      }

      BiConsumer<Integer, String> progressConsumer = progress == null ? (completed, detail) -> {
      } : progress;
      int startingUnits = completedOffset;
      progressConsumer.accept(completedOffset, "Loading " + keys.size() + " ETH canopy-height source tiles");
      return ParallelDownloadRunner.run(
         ParallelDownloadRunner.scope(
            "canopy-height-memory",
            TellusCacheRegistry.generation(TellusCacheDomain.CANOPY_HEIGHT)
         ),
         keys,
         completedOffset,
         this::loadTileIntoCache,
         (key, completed, phaseTotal) -> progressConsumer.accept(
            completed,
            "Loaded ETH canopy-height tile "
               + (completed - startingUnits)
               + "/"
               + phaseTotal
               + " ("
               + key.level()
               + "/"
               + key.row()
               + "/"
               + key.column()
               + ")"
         )
      );
   }

   private void loadTileIntoCache(TileKey key) {
      long generation = TellusCacheRegistry.generation(TellusCacheDomain.CANOPY_HEIGHT);
      this.tile(key, LookupMode.BLOCKING);
      if (Thread.currentThread().isInterrupted()) {
         throw new java.util.concurrent.CancellationException("Interrupted while preloading ETH canopy-height tile " + key);
      }
      if (!TellusCacheRegistry.isCurrent(TellusCacheDomain.CANOPY_HEIGHT, generation)) {
         this.cache.invalidate(key);
         throw new IllegalStateException("Discarded stale ETH canopy-height preload for " + key);
      }
   }

   private int sampleGlobalPixel(int level, long globalPixelX, long globalPixelY, LookupMode lookupMode) {
      if (globalPixelX < 0L || globalPixelY < 0L) {
         return -1;
      }
      int tileColumn = (int)Math.floorDiv(globalPixelX, TILE_SIZE);
      int tileRow = (int)Math.floorDiv(globalPixelY, TILE_SIZE);
      int pixelX = (int)Math.floorMod(globalPixelX, TILE_SIZE);
      int pixelY = (int)Math.floorMod(globalPixelY, TILE_SIZE);
      RasterTile tile = this.tile(new TileKey(level, tileRow, tileColumn), lookupMode);
      return tile == null ? -1 : tile.sample(pixelX, pixelY);
   }

   private RasterTile tile(TileKey key, LookupMode lookupMode) {
      RasterTile memoryTile = this.cache.getIfPresent(key);
      if (memoryTile != null || lookupMode == LookupMode.MEMORY_ONLY) {
         return memoryTile;
      }

      Long retryAt = this.failedUntil.getIfPresent(key);
      if (retryAt != null && System.nanoTime() < retryAt) {
         return null;
      }

      try {
         return this.cache.get(key, () -> this.loadTile(key, lookupMode));
      } catch (ExecutionException | RuntimeException error) {
         if (lookupMode != LookupMode.BLOCKING) {
            return null;
         }
         this.failedUntil.put(key, System.nanoTime() + FAILURE_RETRY_NANOS);
         Throwable cause = error instanceof ExecutionException && error.getCause() != null ? error.getCause() : error;
         if (this.serviceFailureLogged.compareAndSet(false, true)) {
            Tellus.LOGGER.warn("ETH canopy-height tiles are currently unavailable; procedural trees will use biome fallbacks", cause);
         } else {
            Tellus.LOGGER.debug("Failed to load ETH canopy tile {}", key, cause);
         }
         return null;
      }
   }

   private RasterTile loadTile(TileKey key, LookupMode lookupMode) throws IOException {
      Path cachePath = this.cachePath(key);
      if (Files.isRegularFile(cachePath)) {
         try {
            long cachedSize = Files.size(cachePath);
            if (cachedSize > 0L && cachedSize <= MAX_RESPONSE_BYTES) {
               byte[] bytes = Files.readAllBytes(cachePath);
               return RasterTile.from(this.decode(bytes));
            }
            throw new IOException("Unexpected cached ETH canopy tile size " + cachedSize);
         } catch (IOException | RuntimeException error) {
            Tellus.LOGGER.debug("Discarding invalid cached ETH canopy tile {}", cachePath, error);
            Files.deleteIfExists(cachePath);
         }
      }

      if (lookupMode != LookupMode.BLOCKING || ManagedTerrainNetworkPolicy.isCacheOnly()) {
         throw new IOException("ETH canopy tile is not in the local cache");
      }

      byte[] bytes = this.fetchTile(key);
      RasterTile tile = RasterTile.from(this.decode(bytes));
      long generation = TellusCacheRegistry.generation(TellusCacheDomain.CANOPY_HEIGHT);
      if (TellusCacheFiles.writeBytesIfCurrent(TellusCacheDomain.CANOPY_HEIGHT, generation, cachePath, bytes)) {
         this.pruneDiskCachePeriodically();
      }
      this.serviceFailureLogged.set(false);
      return tile;
   }

   private LercU8Decoder.DecodedRaster decode(byte[] bytes) throws IOException {
      LercU8Decoder decoder;
      try {
         decoder = this.decoders.take();
      } catch (InterruptedException error) {
         Thread.currentThread().interrupt();
         throw new IOException("Interrupted while waiting for an ETH canopy-height decoder", error);
      }

      try {
         return decoder.decode(bytes);
      } finally {
         this.decoders.offer(decoder);
      }
   }

   private byte[] fetchTile(TileKey key) throws IOException {
      URI uri = URI.create(this.serviceUrl + "/tile/" + key.level() + "/" + key.row() + "/" + key.column());
      IOException lastError = null;
      for (int attempt = 1; attempt <= FETCH_ATTEMPTS; attempt++) {
         try {
            HttpRequest request = HttpRequest.newBuilder(uri)
               .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
               .header("Accept", "application/octet-stream")
               .header("User-Agent", "Tellus-Minecraft-Mod/" + MinecraftRelease.VERSION)
               .GET()
               .build();
            HttpResponse<byte[]> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
               throw new IOException("HTTP " + response.statusCode() + " from ETH canopy tile service");
            }
            byte[] body = response.body();
            if (body.length == 0 || body.length > MAX_RESPONSE_BYTES) {
               throw new IOException("Unexpected ETH canopy tile size " + body.length);
            }
            return body;
         } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading ETH canopy tile", error);
         } catch (IOException error) {
            lastError = error;
         }
      }
      throw Objects.requireNonNullElseGet(lastError, () -> new IOException("Unable to download ETH canopy tile"));
   }

   private void pruneDiskCachePeriodically() {
      if (this.writesSincePrune.incrementAndGet() < 32) {
         return;
      }
      this.writesSincePrune.set(0);
      try (var paths = Files.walk(this.cacheRoot)) {
         List<Path> files = paths.filter(Files::isRegularFile).toList();
         long total = 0L;
         List<CacheFile> candidates = new ArrayList<>(files.size());
         for (Path file : files) {
            try {
               long size = Files.size(file);
               total += size;
               candidates.add(new CacheFile(file, size, Files.getLastModifiedTime(file).toMillis()));
            } catch (IOException ignored) {
            }
         }
         if (total <= DISK_CACHE_BYTES) {
            return;
         }
         candidates.sort(Comparator.comparingLong(CacheFile::modifiedMillis));
         for (CacheFile candidate : candidates) {
            if (total <= DISK_CACHE_BYTES) {
               break;
            }
            try {
               if (Files.deleteIfExists(candidate.path())) {
                  total -= candidate.size();
               }
            } catch (IOException ignored) {
            }
         }
      } catch (IOException error) {
         Tellus.LOGGER.debug("Unable to prune ETH canopy-height cache", error);
      }
   }

   private Path cachePath(TileKey key) {
      return this.cacheRoot.resolve(Integer.toString(key.level())).resolve(Integer.toString(key.row())).resolve(key.column() + ".lerc");
   }

   static int levelForResolution(double worldScale, double previewResolutionMeters) {
      double requested = Math.max(NATIVE_RESOLUTION_METERS, finitePositive(previewResolutionMeters, worldScale) / 3.0);
      double ratio = requested / NATIVE_RESOLUTION_METERS;
      int overviewSteps = ratio <= 1.0 ? 0 : (int)Math.floor(Math.log(ratio) / Math.log(2.0));
      return Math.max(MIN_LEVEL, Math.min(NATIVE_LEVEL, NATIVE_LEVEL - overviewSteps));
   }

   static TileKey tileKey(double longitude, double latitude, int level) {
      PixelPosition position = pixelPosition(longitude, latitude, level);
      return position == null
         ? null
         : new TileKey(
            level,
            (int)Math.floorDiv(position.globalPixelY(), TILE_SIZE),
            (int)Math.floorDiv(position.globalPixelX(), TILE_SIZE)
         );
   }

   private static PixelPosition pixelPosition(double longitude, double latitude, int level) {
      if (!withinCoverage(longitude, latitude) || level < MIN_LEVEL || level > NATIVE_LEVEL) {
         return null;
      }
      double resolution = resolutionDegrees(level);
      long pixelX = (long)Math.floor((longitude - ORIGIN_LONGITUDE) / resolution);
      long pixelY = (long)Math.floor((ORIGIN_LATITUDE - latitude) / resolution);
      return pixelX < 0L || pixelY < 0L ? null : new PixelPosition(pixelX, pixelY);
   }

   private static List<TileKey> tileKeysForArea(
      int minBlockX,
      int minBlockZ,
      int maxBlockX,
      int maxBlockZ,
      WorldProjection projection,
      double previewResolutionMeters
   ) {
      GeoPoint first = geoPoint(Math.min(minBlockX, maxBlockX), Math.min(minBlockZ, maxBlockZ), projection);
      GeoPoint second = geoPoint(Math.max(minBlockX, maxBlockX), Math.max(minBlockZ, maxBlockZ), projection);
      if (first == null || second == null) {
         return List.of();
      }

      double minLat = Math.max(MIN_LATITUDE, Math.min(first.latitude(), second.latitude()));
      double maxLat = Math.min(Math.nextDown(MAX_LATITUDE), Math.max(first.latitude(), second.latitude()));
      if (minLat > maxLat) {
         return List.of();
      }
      double[][] longitudeRanges = projection.isCentered() && second.longitude() < first.longitude()
         ? new double[][]{{first.longitude(), Math.nextDown(180.0)}, {-180.0, second.longitude()}}
         : new double[][]{{
            Math.max(-180.0, Math.min(first.longitude(), second.longitude())),
            Math.min(Math.nextDown(180.0), Math.max(first.longitude(), second.longitude()))
         }};

      int level = levelForResolution(projection.worldScale(), previewResolutionMeters);
      List<TileRange> ranges;
      long tileCount;
      do {
         ranges = new ArrayList<>(longitudeRanges.length);
         tileCount = 0L;
         for (double[] longitudeRange : longitudeRanges) {
            TileKey northWest = tileKey(longitudeRange[0], maxLat, level);
            TileKey southEast = tileKey(longitudeRange[1], minLat, level);
            if (northWest == null || southEast == null) {
               return List.of();
            }
            ranges.add(new TileRange(northWest, southEast));
            tileCount += (long)(southEast.row() - northWest.row() + 1)
               * (long)(southEast.column() - northWest.column() + 1);
         }
         if (tileCount <= MAX_AREA_TILE_COUNT || level == MIN_LEVEL) {
            break;
         }
         level--;
      } while (true);

      List<TileKey> keys = new ArrayList<>((int)Math.min(Integer.MAX_VALUE, tileCount));
      for (TileRange range : ranges) {
         for (int row = range.northWest().row(); row <= range.southEast().row(); row++) {
            for (int column = range.northWest().column(); column <= range.southEast().column(); column++) {
               keys.add(new TileKey(level, row, column));
            }
         }
      }
      return List.copyOf(keys);
   }

   private static GeoPoint geoPoint(double blockX, double blockZ, WorldProjection projection) {
      if (!(projection.worldScale() > 0.0) || !Double.isFinite(blockX) || !Double.isFinite(blockZ)) {
         return null;
      }
      return new GeoPoint(projection.blockXToLon(blockX), projection.blockZToLat(blockZ));
   }

   private static double resolutionDegrees(int level) {
      return NATIVE_RESOLUTION_DEGREES * (1L << (NATIVE_LEVEL - level));
   }

   private static boolean withinCoverage(double longitude, double latitude) {
      return Double.isFinite(longitude)
         && Double.isFinite(latitude)
         && longitude >= -180.0
         && longitude < 180.0
         && latitude >= MIN_LATITUDE
         && latitude < MAX_LATITUDE;
   }

   private static double percentile(int[] sorted, int length, double fraction) {
      if (length == 1) {
         return sorted[0];
      }
      double index = Math.max(0.0, Math.min(length - 1.0, fraction * (length - 1.0)));
      int lower = (int)Math.floor(index);
      int upper = Math.min(length - 1, lower + 1);
      double blend = index - lower;
      return sorted[lower] * (1.0 - blend) + sorted[upper] * blend;
   }

   private static double finitePositive(double primary, double fallback) {
      if (Double.isFinite(primary) && primary > 0.0) {
         return primary;
      }
      return Double.isFinite(fallback) && fallback > 0.0 ? fallback : NATIVE_RESOLUTION_METERS;
   }

   private static LookupMode managedLookupMode() {
      return ManagedTerrainNetworkPolicy.isCacheOnly() ? LookupMode.LOCAL_ONLY : LookupMode.BLOCKING;
   }

   private static int intProperty(String key, int fallback, int minimum, int maximum) {
      String value = System.getProperty(key);
      if (value == null || value.isBlank()) {
         return fallback;
      }
      try {
         return Math.max(minimum, Math.min(maximum, Integer.parseInt(value.trim())));
      } catch (NumberFormatException error) {
         Tellus.LOGGER.warn("Invalid integer system property {}={}, using {}", key, value, fallback);
         return fallback;
      }
   }

   private static String configuredServiceUrl() {
      String configured = System.getProperty("tellus.canopyHeight.serviceUrl", SERVICE_URL);
      String normalized = configured == null || configured.isBlank() ? SERVICE_URL : configured.trim();
      while (normalized.endsWith("/")) {
         normalized = normalized.substring(0, normalized.length() - 1);
      }
      return normalized;
   }

   @Override
   public TellusCacheDomain cacheDomain() {
      return TellusCacheDomain.CANOPY_HEIGHT;
   }

   @Override
   public void clearCache() {
      this.cache.invalidateAll();
      this.cache.cleanUp();
      this.failedUntil.invalidateAll();
      this.failedUntil.cleanUp();
      this.serviceFailureLogged.set(false);
   }

   private enum LookupMode {
      BLOCKING,
      LOCAL_ONLY,
      MEMORY_ONLY
   }

   public record CanopySample(
      boolean available,
      double centerHeightMeters,
      double meanHeightMeters,
      double medianHeightMeters,
      double percentile75Meters,
      double percentile90Meters,
      double maximumHeightMeters,
      int sourceLevel,
      int validSampleCount
   ) {
      private static CanopySample unavailable() {
         return new CanopySample(false, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, -1, 0);
      }
   }

   static record TileKey(int level, int row, int column) {
   }

   private record PixelPosition(long globalPixelX, long globalPixelY) {
   }

   private record GeoPoint(double longitude, double latitude) {
   }

   private record TileRange(TileKey northWest, TileKey southEast) {
   }

   private record CacheFile(Path path, long size, long modifiedMillis) {
   }

   private static final class RasterTile {
      private final int width;
      private final int height;
      private final byte[] pixels;
      private final byte[] mask;

      private RasterTile(int width, int height, byte[] pixels, byte[] mask) {
         this.width = width;
         this.height = height;
         this.pixels = pixels;
         this.mask = mask;
      }

      private static RasterTile from(LercU8Decoder.DecodedRaster decoded) throws IOException {
         if (decoded.width() != TILE_SIZE || decoded.height() != TILE_SIZE) {
            throw new IOException("Unexpected ETH canopy tile size " + decoded.width() + "x" + decoded.height());
         }
         return new RasterTile(decoded.width(), decoded.height(), decoded.pixels(), decoded.mask());
      }

      private int sample(int pixelX, int pixelY) {
         if (pixelX < 0 || pixelY < 0 || pixelX >= this.width || pixelY >= this.height) {
            return -1;
         }
         int index = pixelY * this.width + pixelX;
         return this.mask != null && this.mask[index] == 0 ? -1 : this.pixels[index] & 255;
      }
   }
}
