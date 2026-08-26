package com.yucareux.tellus.world.data.mask;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.cache.TellusCacheDomain;
import com.yucareux.tellus.cache.TellusCacheFiles;
import com.yucareux.tellus.cache.TellusCacheHandle;
import com.yucareux.tellus.cache.TellusCacheRegistry;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.platform.TellusPlatform;
import com.yucareux.tellus.world.data.source.ParallelDownloadRunner;
import com.yucareux.tellus.world.data.source.MapTileImageValidator;
import com.yucareux.tellus.worldgen.WorldProjection;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import javax.imageio.ImageIO;
import net.minecraft.util.Mth;

public final class TellusLandMaskSource implements TellusCacheHandle {
   private static final double EQUATOR_CIRCUMFERENCE = 4.0075017E7;
   private static final int TILE_SIZE = 256;
   private static final int MAX_TILE_BYTES = 4 * 1024 * 1024;
   private static final int MAX_TILE_DIMENSION = 512;
   private static final double MIN_LAT = -85.05112878;
   private static final double MAX_LAT = 85.05112878;
   private static final double MIN_LON = -180.0;
   private static final double MAX_LON = 180.0;
   private static final String DEFAULT_BASE_URL = "https://github.com/Yucareux/Tellus-Land-Polygons/releases/download/v1.0.0/";
   private static final String PMTILES_NAME = "tellus_landmask.pmtiles";
   private static final int MAX_CACHE_TILES = intProperty("tellus.landmask.cacheTiles", 256);
   private static final int AREA_PREFETCH_SAMPLE_POINTS = intProperty("tellus.landmask.areaPrefetch.samples", 25);
   private static final int AREA_PREFETCH_TILE_LIMIT = intProperty("tellus.landmask.areaPrefetch.tileLimit", 512);
   private static final AtomicBoolean LOGGED_UNAVAILABLE = new AtomicBoolean(false);
   private final PmTilesReader reader;
   private final Path cacheRoot;
   private final LoadingCache<TellusLandMaskSource.TileKey, TellusLandMaskSource.LandMaskTile> cache;
   private final int minZoom;
   private final int maxZoom;
   private final boolean available;

   public TellusLandMaskSource() {
      String baseUrl = System.getProperty("tellus.landmask.baseUrl", DEFAULT_BASE_URL);
      String sourceUrl = normalizeBaseUrl(baseUrl) + PMTILES_NAME;
      this.reader = new PmTilesReader(sourceUrl);
      this.cacheRoot = TellusPlatform.gameDir()
         .resolve("tellus/cache/map/land-mask")
         .resolve(Integer.toUnsignedString(sourceUrl.hashCode(), 36));
      int resolvedMin = 0;
      int resolvedMax = 0;
      boolean ok = false;

      try {
         PmTilesReader.PmTilesHeader header = this.reader.header();
         resolvedMin = header.minZoom();
         resolvedMax = header.maxZoom();
         ok = true;
      } catch (IOException var6) {
         logUnavailable(var6);
      }

      this.available = ok;
      this.minZoom = ok ? resolvedMin : 0;
      this.maxZoom = ok ? resolvedMax : 0;
      this.cache = CacheBuilder.newBuilder()
         .maximumSize(MAX_CACHE_TILES)
         .build(new CacheLoader<TellusLandMaskSource.TileKey, TellusLandMaskSource.LandMaskTile>() {
            
            public TellusLandMaskSource.LandMaskTile load(TellusLandMaskSource.TileKey key) throws Exception {
               return TellusLandMaskSource.this.loadTile(key);
            }
         });
      TellusCacheRegistry.register(this);
   }

   public TellusLandMaskSource.LandMaskSample sampleLandMask(double blockX, double blockZ, WorldProjection projection) {
      return this.sampleLandMask(blockX, blockZ, projection, ManagedTerrainNetworkPolicy.isCacheOnly());
   }

   public TellusLandMaskSource.LandMaskSample sampleLandMaskLocalOnly(double blockX, double blockZ, WorldProjection projection) {
      return this.sampleLandMask(blockX, blockZ, projection, true);
   }

   public TellusLandMaskSource.LandMaskSampler newSampler() {
      return new TellusLandMaskSource.LandMaskSampler();
   }

   private TellusLandMaskSource.LandMaskSample sampleLandMask(double blockX, double blockZ, WorldProjection projection, boolean localOnly) {
      if (this.available && !(projection.worldScale() <= 0.0)) {
         double lon = projection.blockXToLon(blockX);
         double lat = projection.blockZToLat(blockZ);
         if (!(lat < MIN_LAT) && !(lat > MAX_LAT) && !(lon < MIN_LON) && !(lon > MAX_LON)) {
            int zoom = this.selectZoom(projection.worldScale());
            TellusLandMaskSource.TileKey key = tileKeyForLonLat(lon, lat, zoom);
            if (key == null) {
               return TellusLandMaskSource.LandMaskSample.unknown();
            } else {
               TellusLandMaskSource.LandMaskTile tile = localOnly ? this.getTileLocalOnly(key) : this.getTile(key);
               if (tile == null) {
                  return TellusLandMaskSource.LandMaskSample.unknown();
               } else if (tile.isEmpty()) {
                  return TellusLandMaskSource.LandMaskSample.known(false);
               } else {
                  double latRad = Math.toRadians(lat);
                  double n = Math.pow(2.0, zoom);
                  double x = (lon + MAX_LON) / (MAX_LON - MIN_LON) * n;
                  double y = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n;
                  int tileX = Mth.floor(x);
                  int tileY = Mth.floor(y);
                  double localX = (x - tileX) * TILE_SIZE;
                  double localY = (y - tileY) * TILE_SIZE;
                  int px = Mth.clamp((int)localX, 0, tile.width() - 1);
                  int py = Mth.clamp((int)localY, 0, tile.height() - 1);
                  return TellusLandMaskSource.LandMaskSample.known(tile.isLand(px, py));
               }
            }
         } else {
            return TellusLandMaskSource.LandMaskSample.unknown();
         }
      } else {
         return TellusLandMaskSource.LandMaskSample.unknown();
      }
   }

   public void prefetchTiles(double blockX, double blockZ, WorldProjection projection, int radius) {
      if (this.available && !(projection.worldScale() <= 0.0) && radius > 0) {
         int zoom = this.selectZoom(projection.worldScale());
         TellusLandMaskSource.TileKey center = tileKeyForBlock(blockX, blockZ, projection, zoom);
         if (center != null) {
            int tilesPerAxis = 1 << zoom;
            int minX = Math.max(0, center.x() - radius);
            int maxX = Math.min(tilesPerAxis - 1, center.x() + radius);
            int minY = Math.max(0, center.y() - radius);
            int maxY = Math.min(tilesPerAxis - 1, center.y() + radius);

            for (int tileY = minY; tileY <= maxY; tileY++) {
               for (int tileX = minX; tileX <= maxX; tileX++) {
                  this.getTile(new TellusLandMaskSource.TileKey(zoom, tileX, tileY));
               }
            }
         }
      }
   }

   public int preloadAreaTaskCount(double minBlockX, double minBlockZ, double maxBlockX, double maxBlockZ, WorldProjection projection) {
      return Math.max(1, this.areaTileKeys(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection).size());
   }

   public int preloadAreaInputs(
      double minBlockX,
      double minBlockZ,
      double maxBlockX,
      double maxBlockZ,
      WorldProjection projection,
      int completedUnits,
      BiConsumer<Integer, String> progressConsumer
   ) {
      return this.preloadAreaInputs(
         minBlockX,
         minBlockZ,
         maxBlockX,
         maxBlockZ,
         projection,
         completedUnits,
         progressConsumer,
         false
      );
   }

   public int preloadAreaInputsIntoMemory(
      double minBlockX,
      double minBlockZ,
      double maxBlockX,
      double maxBlockZ,
      WorldProjection projection,
      int completedUnits,
      BiConsumer<Integer, String> progressConsumer
   ) {
      return this.preloadAreaInputs(
         minBlockX,
         minBlockZ,
         maxBlockX,
         maxBlockZ,
         projection,
         completedUnits,
         progressConsumer,
         true
      );
   }

   private int preloadAreaInputs(
      double minBlockX,
      double minBlockZ,
      double maxBlockX,
      double maxBlockZ,
      WorldProjection projection,
      int completedUnits,
      BiConsumer<Integer, String> progressConsumer,
      boolean loadIntoMemory
   ) {
      BiConsumer<Integer, String> progress = progressConsumer == null ? (completed, detail) -> {
      } : progressConsumer;
      List<TellusLandMaskSource.TileKey> keys = this.areaTileKeys(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection);
      if (keys.isEmpty()) {
         progress.accept(completedUnits, "Skipping land mask preload because land mask data is unavailable");
         return completedUnits + 1;
      }

      int startingUnits = completedUnits;
      progress.accept(completedUnits, (loadIntoMemory ? "Loading " : "Downloading ") + keys.size() + " land-mask source tiles");
      return ParallelDownloadRunner.run(
         ParallelDownloadRunner.scope(
            loadIntoMemory ? "land-mask-memory" : "land-mask",
            TellusCacheRegistry.generation(TellusCacheDomain.OSM)
         ),
         keys,
         completedUnits,
         loadIntoMemory ? this::loadTileIntoCache : this::downloadRawTile,
         (key, completed, phaseTotal) -> progress.accept(
            completed,
            (loadIntoMemory ? "Loaded " : "Cached ")
               + "land-mask tile "
               + (completed - startingUnits)
               + "/"
               + phaseTotal
               + " ("
               + key.label()
               + ")"
         )
      );
   }

   private List<TellusLandMaskSource.TileKey> areaTileKeys(
      double minBlockX, double minBlockZ, double maxBlockX, double maxBlockZ, WorldProjection projection
   ) {
      if (!this.available || projection.worldScale() <= 0.0) {
         return List.of();
      }

      LinkedHashSet<TellusLandMaskSource.TileKey> keys = new LinkedHashSet<>();
      TellusLandMaskSource.TileBounds bounds = this.tileBoundsForArea(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection);
      if (bounds != null && bounds.count() <= AREA_PREFETCH_TILE_LIMIT) {
         for (int tileY = bounds.minY(); tileY <= bounds.maxY(); tileY++) {
            for (TellusLandMaskSource.TileXRange range : bounds.xRanges()) {
               for (int tileX = range.minX(); tileX <= range.maxX(); tileX++) {
                  keys.add(new TellusLandMaskSource.TileKey(bounds.zoom(), tileX, tileY));
               }
            }
         }
      } else {
         int zoom = this.selectZoom(projection.worldScale());
         for (TellusLandMaskSource.AreaSample sample : areaSamples(minBlockX, minBlockZ, maxBlockX, maxBlockZ)) {
            TellusLandMaskSource.TileKey key = tileKeyForBlock(sample.blockX(), sample.blockZ(), projection, zoom);
            if (key != null) {
               keys.add(key);
            }
         }
      }
      return List.copyOf(keys);
   }

   private TellusLandMaskSource.TileBounds tileBoundsForArea(
      double minBlockX, double minBlockZ, double maxBlockX, double maxBlockZ, WorldProjection projection
   ) {
      if (!this.available || projection.worldScale() <= 0.0) {
         return null;
      }

      int zoom = this.selectZoom(projection.worldScale());
      int tilesPerAxis = 1 << zoom;
      Set<TellusLandMaskSource.TileKey> corners = new LinkedHashSet<>(4);
      double minX = Math.min(minBlockX, maxBlockX);
      double maxX = Math.max(minBlockX, maxBlockX);
      double minZ = Math.min(minBlockZ, maxBlockZ);
      double maxZ = Math.max(minBlockZ, maxBlockZ);
      double[] xs = new double[]{minX, maxX};
      double[] zs = new double[]{minZ, maxZ};
      for (double x : xs) {
         for (double z : zs) {
            TellusLandMaskSource.TileKey key = tileKeyForBlock(x, z, projection, zoom);
            if (key != null) {
               corners.add(key);
            }
         }
      }

      if (corners.isEmpty()) {
         return null;
      }

      int tileMinX = Integer.MAX_VALUE;
      int tileMaxX = Integer.MIN_VALUE;
      int tileMinY = Integer.MAX_VALUE;
      int tileMaxY = Integer.MIN_VALUE;
      for (TellusLandMaskSource.TileKey key : corners) {
         tileMinX = Math.min(tileMinX, key.x());
         tileMaxX = Math.max(tileMaxX, key.x());
         tileMinY = Math.min(tileMinY, key.y());
         tileMaxY = Math.max(tileMaxY, key.y());
      }

      int clampedMinX = Mth.clamp(tileMinX, 0, tilesPerAxis - 1);
      int clampedMaxX = Mth.clamp(tileMaxX, 0, tilesPerAxis - 1);
      boolean wrapsLongitude = projection.isCentered()
         && projection.blockXToLon(minX) > projection.blockXToLon(maxX);
      List<TellusLandMaskSource.TileXRange> xRanges = wrapsLongitude
         ? List.of(
            new TellusLandMaskSource.TileXRange(clampedMaxX, tilesPerAxis - 1),
            new TellusLandMaskSource.TileXRange(0, clampedMinX)
         )
         : List.of(new TellusLandMaskSource.TileXRange(clampedMinX, clampedMaxX));
      return new TellusLandMaskSource.TileBounds(
         zoom, xRanges, Mth.clamp(tileMinY, 0, tilesPerAxis - 1), Mth.clamp(tileMaxY, 0, tilesPerAxis - 1)
      );
   }

   private static List<TellusLandMaskSource.AreaSample> areaSamples(
      double minBlockX, double minBlockZ, double maxBlockX, double maxBlockZ
   ) {
      int samplesPerAxis = Math.max(1, (int)Math.ceil(Math.sqrt(AREA_PREFETCH_SAMPLE_POINTS)));
      double minX = Math.min(minBlockX, maxBlockX);
      double maxX = Math.max(minBlockX, maxBlockX);
      double minZ = Math.min(minBlockZ, maxBlockZ);
      double maxZ = Math.max(minBlockZ, maxBlockZ);
      List<TellusLandMaskSource.AreaSample> samples = new ArrayList<>(samplesPerAxis * samplesPerAxis);
      if (samplesPerAxis == 1) {
         samples.add(new TellusLandMaskSource.AreaSample((minX + maxX) * 0.5, (minZ + maxZ) * 0.5));
         return samples;
      }

      for (int z = 0; z < samplesPerAxis; z++) {
         double tz = (double)z / (samplesPerAxis - 1);
         double blockZ = Mth.lerp(tz, minZ, maxZ);
         for (int x = 0; x < samplesPerAxis; x++) {
            double tx = (double)x / (samplesPerAxis - 1);
            double blockX = Mth.lerp(tx, minX, maxX);
            samples.add(new TellusLandMaskSource.AreaSample(blockX, blockZ));
         }
      }

      return samples;
   }

   private TellusLandMaskSource.LandMaskTile getTile( TellusLandMaskSource.TileKey key) {
      try {
         return (TellusLandMaskSource.LandMaskTile)this.cache.get(key);
      } catch (Exception var3) {
         Tellus.LOGGER.debug("Failed to load land mask tile {}", key, var3);
         return null;
      }
   }

   private TellusLandMaskSource.LandMaskTile getTileLocalOnly(TellusLandMaskSource.TileKey key) {
      TellusLandMaskSource.LandMaskTile cached = this.cache.getIfPresent(key);
      if (cached != null) {
         return cached;
      }
      Path cachePath = this.cachePath(key);
      if (!Files.isRegularFile(cachePath)) {
         return null;
      }
      try {
         TellusLandMaskSource.LandMaskTile local = decodeTile(readCachedTile(cachePath));
         TellusLandMaskSource.LandMaskTile raced = this.cache.asMap().putIfAbsent(key, local);
         return raced == null ? local : raced;
      } catch (IOException error) {
         Tellus.LOGGER.debug("Failed to read cached land mask tile {}", key, error);
         try {
            Files.deleteIfExists(cachePath);
         } catch (IOException deleteError) {
            error.addSuppressed(deleteError);
         }
         return null;
      }
   }

   private TellusLandMaskSource.LandMaskTile loadTile(TellusLandMaskSource.TileKey key) throws IOException {
      TellusLandMaskSource.TileKey resolvedKey = Objects.requireNonNull(key, "key");
      Path cachePath = this.cachePath(resolvedKey);
      byte[] bytes;
      if (Files.isRegularFile(cachePath)) {
         try {
            return decodeTile(readCachedTile(cachePath));
         } catch (IOException invalidCache) {
            Files.deleteIfExists(cachePath);
            Tellus.LOGGER.debug("Discarded invalid cached land mask tile {}", resolvedKey, invalidCache);
         }
      }

      long generation = TellusCacheRegistry.generation(TellusCacheDomain.OSM);
      bytes = this.reader.getTileBytes(resolvedKey.zoom(), resolvedKey.x(), resolvedKey.y());
      validateTileBytes(bytes);
      this.cacheRawTile(cachePath, bytes == null ? new byte[0] : bytes, generation);
      return decodeTile(bytes);
   }

   private static TellusLandMaskSource.LandMaskTile decodeTile(byte[] bytes) throws IOException {
      if (bytes == null || bytes.length == 0) {
         return TellusLandMaskSource.LandMaskTile.empty();
      } else {
         validateTileBytes(bytes);
         BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
         if (image == null) {
            throw new IOException("Invalid land mask tile image");
         } else {
            int width = image.getWidth();
            int height = image.getHeight();
            byte[] mask = new byte[width * height];

            for (int y = 0; y < height; y++) {
               int row = y * width;

               for (int x = 0; x < width; x++) {
                  int value = image.getRaster().getSample(x, y, 0);
                  mask[row + x] = (byte)(value > 0 ? 1 : 0);
               }
            }

            return new TellusLandMaskSource.LandMaskTile(width, height, mask, false);
         }
      }
   }

   private static byte[] readCachedTile(Path cachePath) throws IOException {
      long size = Files.size(cachePath);
      if (size > MAX_TILE_BYTES) {
         throw new IOException("Cached land mask tile exceeds the " + MAX_TILE_BYTES + " byte safety limit");
      }
      try (var input = Files.newInputStream(cachePath)) {
         return MapTileImageValidator.readBounded(input, MAX_TILE_BYTES);
      }
   }

   private static void validateTileBytes(byte[] bytes) throws IOException {
      if (bytes == null || bytes.length == 0) {
         return;
      }
      if (bytes.length > MAX_TILE_BYTES) {
         throw new IOException("Land mask tile exceeds the " + MAX_TILE_BYTES + " byte safety limit");
      }
      MapTileImageValidator.validatePng(bytes, MAX_TILE_DIMENSION, MAX_TILE_DIMENSION);
   }

   private void downloadRawTile(TellusLandMaskSource.TileKey key) {
      Path cachePath = this.cachePath(key);
      if (Files.isRegularFile(cachePath)) {
         try {
            validateTileBytes(readCachedTile(cachePath));
            return;
         } catch (IOException invalidCache) {
            try {
               Files.deleteIfExists(cachePath);
            } catch (IOException deleteError) {
               invalidCache.addSuppressed(deleteError);
            }
            Tellus.LOGGER.debug("Discarded invalid cached land mask tile {}", key, invalidCache);
         }
      }

      try {
         long generation = TellusCacheRegistry.generation(TellusCacheDomain.OSM);
         byte[] bytes = this.reader.getTileBytes(key.zoom(), key.x(), key.y());
         validateTileBytes(bytes);
         this.cacheRawTile(cachePath, bytes == null ? new byte[0] : bytes, generation);
      } catch (IOException error) {
         throw new RuntimeException("Failed to download land mask tile " + key.label(), error);
      }
   }

   private void loadTileIntoCache(TellusLandMaskSource.TileKey key) {
      long generation = TellusCacheRegistry.generation(TellusCacheDomain.OSM);
      try {
         this.cache.get(key);
      } catch (Exception error) {
         throw new RuntimeException("Failed to preload land mask tile " + key.label(), error);
      }
      if (Thread.currentThread().isInterrupted()) {
         throw new java.util.concurrent.CancellationException("Interrupted while preloading land mask tile " + key.label());
      }
      if (!TellusCacheRegistry.isCurrent(TellusCacheDomain.OSM, generation)) {
         this.cache.invalidate(key);
         throw new IllegalStateException("Discarded stale land mask preload for " + key.label());
      }
   }

   private void cacheRawTile(Path cachePath, byte[] bytes, long generation) throws IOException {
      if (!TellusCacheFiles.writeBytesIfCurrent(TellusCacheDomain.OSM, generation, cachePath, bytes)) {
         throw new IOException("Discarded stale land mask cache write for " + cachePath);
      }
   }

   private Path cachePath(TellusLandMaskSource.TileKey key) {
      return this.cacheRoot.resolve(key.zoom() + "/" + key.x() + "/" + key.y() + ".png");
   }

   private int selectZoom(double worldScale) {
      if (this.available && !(worldScale <= 0.0)) {
         return selectZoom(worldScale, this.minZoom, this.maxZoom);
      } else {
         return this.minZoom;
      }
   }

   static int selectZoom(double worldScale, int minZoom, int maxZoom) {
      int low = Math.max(0, Math.min(minZoom, maxZoom));
      int high = Math.max(low, maxZoom);
      if (!(Double.isFinite(worldScale) && worldScale > 0.0)) {
         return low;
      }
      double raw = Math.log(EQUATOR_CIRCUMFERENCE / (TILE_SIZE * worldScale)) / Math.log(2.0);
      return Mth.clamp((int)Math.round(raw), low, high);
   }

   private static TellusLandMaskSource.TileKey tileKeyForBlock(double blockX, double blockZ, WorldProjection projection, int zoom) {
      double lon = projection.blockXToLon(blockX);
      double lat = projection.blockZToLat(blockZ);
      return tileKeyForLonLat(lon, lat, zoom);
   }

   private static TellusLandMaskSource.TileKey tileKeyForLonLat(double lon, double lat, int zoom) {
      if (!(lat < MIN_LAT) && !(lat > MAX_LAT) && !(lon < MIN_LON) && !(lon > MAX_LON)) {
         double latRad = Math.toRadians(lat);
         double n = Math.pow(2.0, zoom);
         double x = (lon + MAX_LON) / (MAX_LON - MIN_LON) * n;
         double y = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n;
         if (!(x < 0.0) && !(y < 0.0) && !(x >= n) && !(y >= n)) {
            int tileX = Mth.floor(x);
            int tileY = Mth.floor(y);
            return new TellusLandMaskSource.TileKey(zoom, tileX, tileY);
         } else {
            return null;
         }
      } else {
         return null;
      }
   }

   private static String normalizeBaseUrl(String baseUrl) {
      Objects.requireNonNull(baseUrl, "baseUrl");
      return baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
   }

   private static void logUnavailable(IOException error) {
      if (LOGGED_UNAVAILABLE.compareAndSet(false, true)) {
         Tellus.LOGGER.info("Land mask PMTiles unavailable ({}); using ESA-only land fallback.", describeError(error));
      }

      Tellus.LOGGER.debug("Land mask PMTiles unavailable; using ESA-only land fallback", error);
   }

   @Override
   public TellusCacheDomain cacheDomain() {
      return TellusCacheDomain.OSM;
   }

   @Override
   public void clearCache() {
      this.cache.invalidateAll();
      this.cache.cleanUp();
   }

   private static String describeError(IOException error) {
      String message = error.getMessage();
      return message != null && !message.isBlank() ? message : error.getClass().getSimpleName();
   }

   private static int intProperty(String key, int defaultValue) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      } else {
         try {
            return Math.max(1, Integer.parseInt(value));
         } catch (NumberFormatException var4) {
            return defaultValue;
         }
      }
   }

   public final class LandMaskSampler {
      private WorldProjection cachedProjection;
      private int cachedZoom;
      private int cachedTileX = Integer.MIN_VALUE;
      private int cachedTileY = Integer.MIN_VALUE;
      private TellusLandMaskSource.LandMaskTile cachedTile;
      private boolean cachedTileSet;

      private LandMaskSampler() {
      }

      public TellusLandMaskSource.LandMaskSample sample(double blockX, double blockZ, WorldProjection projection) {
         if (!TellusLandMaskSource.this.available || projection.worldScale() <= 0.0) {
            return TellusLandMaskSource.LandMaskSample.unknown();
         }

         if (!projection.equals(this.cachedProjection)) {
            this.cachedProjection = projection;
            this.cachedZoom = TellusLandMaskSource.this.selectZoom(projection.worldScale());
            this.cachedTileSet = false;
         }

         double lon = projection.blockXToLon(blockX);
         double lat = projection.blockZToLat(blockZ);
         if (lat < MIN_LAT || lat > MAX_LAT || lon < MIN_LON || lon > MAX_LON) {
            return TellusLandMaskSource.LandMaskSample.unknown();
         }

         double latRad = Math.toRadians(lat);
         double n = Math.pow(2.0, this.cachedZoom);
         double x = (lon + MAX_LON) / (MAX_LON - MIN_LON) * n;
         double y = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n;
         if (x < 0.0 || y < 0.0 || x >= n || y >= n) {
            return TellusLandMaskSource.LandMaskSample.unknown();
         }

         int tileX = Mth.floor(x);
         int tileY = Mth.floor(y);
         TellusLandMaskSource.LandMaskTile tile;
         if (this.cachedTileSet && tileX == this.cachedTileX && tileY == this.cachedTileY) {
            tile = this.cachedTile;
         } else {
            TellusLandMaskSource.TileKey key = new TellusLandMaskSource.TileKey(this.cachedZoom, tileX, tileY);
            tile = ManagedTerrainNetworkPolicy.isCacheOnly()
               ? TellusLandMaskSource.this.getTileLocalOnly(key)
               : TellusLandMaskSource.this.getTile(key);
            this.cachedTile = tile;
            this.cachedTileX = tileX;
            this.cachedTileY = tileY;
            this.cachedTileSet = true;
         }

         if (tile == null) {
            return TellusLandMaskSource.LandMaskSample.unknown();
         } else if (tile.isEmpty()) {
            return TellusLandMaskSource.LandMaskSample.known(false);
         }

         double localX = (x - tileX) * TILE_SIZE;
         double localY = (y - tileY) * TILE_SIZE;
         int px = Mth.clamp((int)localX, 0, tile.width() - 1);
         int py = Mth.clamp((int)localY, 0, tile.height() - 1);
         return TellusLandMaskSource.LandMaskSample.known(tile.isLand(px, py));
      }
   }

   public record LandMaskSample(boolean known, boolean land) {
      public static TellusLandMaskSource.LandMaskSample known(boolean land) {
         return new TellusLandMaskSource.LandMaskSample(true, land);
      }

      public static TellusLandMaskSource.LandMaskSample unknown() {
         return new TellusLandMaskSource.LandMaskSample(false, false);
      }
   }

   private static final class LandMaskTile {
      private static final TellusLandMaskSource.LandMaskTile EMPTY = new TellusLandMaskSource.LandMaskTile(0, 0, new byte[0], true);
      private final int width;
      private final int height;
      private final byte[] mask;
      private final boolean empty;

      private LandMaskTile(int width, int height, byte[] mask, boolean empty) {
         this.width = width;
         this.height = height;
         this.mask = mask;
         this.empty = empty;
      }

      public static TellusLandMaskSource.LandMaskTile empty() {
         return EMPTY;
      }

      public boolean isEmpty() {
         return this.empty;
      }

      public int width() {
         return this.width;
      }

      public int height() {
         return this.height;
      }

      public boolean isLand(int x, int y) {
         if (!this.empty && this.mask.length != 0) {
            int index = y * this.width + x;
            return index >= 0 && index < this.mask.length ? this.mask[index] != 0 : false;
         } else {
            return false;
         }
      }
   }

   private record AreaSample(double blockX, double blockZ) {
   }

   private record TileBounds(int zoom, List<TellusLandMaskSource.TileXRange> xRanges, int minY, int maxY) {
      private int count() {
         long width = this.xRanges.stream().mapToLong(TellusLandMaskSource.TileXRange::count).sum();
         long height = (long)this.maxY - this.minY + 1L;
         long count = Math.max(0L, width) * Math.max(0L, height);
         return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)count;
      }
   }

   private record TileXRange(int minX, int maxX) {
      private long count() {
         return Math.max(0L, (long)this.maxX - this.minX + 1L);
      }
   }

   private record TileKey(int zoom, int x, int y) {
      private String label() {
         return this.zoom + "/" + this.x + "/" + this.y;
      }
   }
}
