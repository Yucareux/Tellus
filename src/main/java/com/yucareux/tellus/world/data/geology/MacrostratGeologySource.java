package com.yucareux.tellus.world.data.geology;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.cache.TellusCacheDomain;
import com.yucareux.tellus.cache.TellusCacheFiles;
import com.yucareux.tellus.cache.TellusCacheHandle;
import com.yucareux.tellus.cache.TellusCacheRegistry;
import com.yucareux.tellus.compat.MinecraftRelease;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.platform.TellusPlatform;
import com.yucareux.tellus.world.data.pmtiles.PmTilesSafety;
import com.yucareux.tellus.world.data.source.RunLengthGridLookup;
import com.yucareux.tellus.world.data.source.SourceInterrupts;
import com.yucareux.tellus.worldgen.EarthProjection;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;

/**
 * Bedrock from Macrostrat's merged geologic maps (macrostrat.org, CC BY 4.0), where local, state or national maps exist.
 *
 * <p>Tiles of the {@code carto} layer are downloaded when a rock face first needs them, at a zoom chosen from the world
 * scale, kept on disk and read into a grid of map units. Each zoom shows the finest map Macrostrat ranks for it, and
 * polygon detail keeps growing to about zoom 14, which fine world scales use. Below zoom 7 the merged maps are
 * continental or world maps, coarser than the bundled GLiM layer, so coarse scales and far LODs get no unit here and the
 * caller uses GLiM.</p>
 */
public final class MacrostratGeologySource implements TellusCacheHandle {
   public static final String DEFAULT_TILE_URL = "https://tiles.macrostrat.org/carto/{z}/{x}/{y}.mvt";
   static final int MIN_ZOOM = 7;
   static final int MAX_ZOOM = 14;
   // A tile spans at least this many samples, and its grid has as many cells, so a cell is one or two samples wide.
   static final int MIN_TILE_WIDTH_SAMPLES = 256;
   static final int GRID_SIZE = 256;
   private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
   // About 130 KB per decoded tile.
   private static final int MEMORY_TILE_COUNT = intProperty("tellus.macrostrat.memoryTiles", 128, 4, 1024);
   private static final int CONNECT_TIMEOUT_SECONDS = intProperty("tellus.macrostrat.connectTimeoutSeconds", 10, 1, 120);
   private static final int REQUEST_TIMEOUT_SECONDS = intProperty("tellus.macrostrat.requestTimeoutSeconds", 30, 1, 180);
   private static final int FETCH_ATTEMPTS = intProperty("tellus.macrostrat.fetchAttempts", 2, 1, 5);
   // Macrostrat is an academic service with no published rate limit, so a few downloads at a time.
   private static final int MAX_CONCURRENT_REQUESTS = intProperty("tellus.macrostrat.maxRequests", 4, 1, 16);
   private static final long FAILURE_RETRY_NANOS = TimeUnit.SECONDS.toNanos(60L);
   private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("tellus.macrostrat.enabled", "true"));
   // Continental and world maps, which GLiM betters, and interpretations of Precambrian basement buried under younger rock.
   private static final Set<Integer> SKIPPED_SOURCES = Set.of(2, 7, 11, 147, 154, 190, 551, 556, 1087, 460, 461, 462, 463, 465, 486, 487, 489);
   private static final long WARP_X_SEED = 0x2F6B9D3A71C4E085L;
   private static final long WARP_Z_SEED = -0x6A1D4C8B3E5F7092L;

   private final String tileUrl = configuredTileUrl();
   private final Path cacheRoot = TellusPlatform.gameDir().resolve("tellus/cache/geology/macrostrat-carto");
   private final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .version(HttpClient.Version.HTTP_2)
      .build();
   private final Semaphore requests = new Semaphore(MAX_CONCURRENT_REQUESTS);
   private final Cache<TileKey, MacrostratTile> cache = CacheBuilder.newBuilder().maximumSize(MEMORY_TILE_COUNT).build();
   private final Cache<TileKey, Long> failedUntil = CacheBuilder.newBuilder().maximumSize(512).build();
   private final AtomicBoolean serviceFailureLogged = new AtomicBoolean();

   private MacrostratGeologySource() {
      TellusCacheRegistry.register(this);
   }

   public static MacrostratGeologySource shared() {
      return SharedHolder.INSTANCE;
   }

   /**
    * Bedrock at a block, or null where Macrostrat has no usable map: no coverage, loose cover, or a scale too coarse.
    * A smooth warp of about a grid cell bends the cells' staircase borders.
    *
    * @param resolutionMeters spacing of the samples, such as a distant LOD's cell; coarser samples read coarser tiles
    */
   public MapUnit sampleUnit(double blockX, double blockZ, double worldScale, double resolutionMeters) {
      if (!ENABLED || !Double.isFinite(blockX) || !Double.isFinite(blockZ) || !(worldScale > 0.0)) {
         return null;
      }
      int zoom = zoomFor(worldScale, resolutionMeters);
      if (zoom < 0) {
         return null;
      }

      double tilesPerAxis = 1 << zoom;
      double cellBlocks = 360.0 * EarthProjection.blocksPerDegree(worldScale) / tilesPerAxis / GRID_SIZE;
      double x = blockX + RunLengthGridLookup.borderWarp(blockX, blockZ, cellBlocks, WARP_X_SEED);
      double z = blockZ + RunLengthGridLookup.borderWarp(blockX, blockZ, cellBlocks, WARP_Z_SEED);
      double longitude = EarthProjection.blockXToLongitude(x, worldScale);
      double latitude = EarthProjection.blockZToLat(z, worldScale);
      if (!(Math.abs(latitude) < EarthProjection.MAX_MERCATOR_LATITUDE)) {
         return null;
      }
      double wrappedLongitude = longitude - 360.0 * Math.floor((longitude + 180.0) / 360.0);
      double latitudeRad = Math.toRadians(latitude);
      double tileX = (wrappedLongitude + 180.0) / 360.0 * tilesPerAxis;
      double tileY = (1.0 - Math.log(Math.tan(latitudeRad) + 1.0 / Math.cos(latitudeRad)) / Math.PI) / 2.0 * tilesPerAxis;
      int column = (int)Math.floor(tileX);
      int row = (int)Math.floor(tileY);
      if (column < 0 || row < 0 || column >= tilesPerAxis || row >= tilesPerAxis) {
         return null;
      }

      MacrostratTile tile = this.tile(new TileKey(zoom, column, row), managedLookupMode());
      return tile == null ? null : tile.unitAt(tileX - column, tileY - row);
   }

   /** Finest zoom whose tiles span {@link #MIN_TILE_WIDTH_SAMPLES} samples, or -1 when that is coarser than {@link #MIN_ZOOM}. */
   static int zoomFor(double worldScale, double resolutionMeters) {
      double sampleBlocks = Double.isFinite(resolutionMeters) ? Math.max(1.0, resolutionMeters / worldScale) : 1.0;
      double worldWidthBlocks = 360.0 * EarthProjection.blocksPerDegree(worldScale);
      for (int zoom = MAX_ZOOM; zoom >= MIN_ZOOM; zoom--) {
         if (worldWidthBlocks / (1 << zoom) >= MIN_TILE_WIDTH_SAMPLES * sampleBlocks) {
            return zoom;
         }
      }
      return -1;
   }

   private MacrostratTile tile(TileKey key, LookupMode lookupMode) {
      for (int attempt = 0; ; attempt++) {
         MacrostratTile memoryTile = this.cache.getIfPresent(key);
         if (memoryTile != null) {
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
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            if (SourceInterrupts.isInterruption(cause)) {
               // A cancelled request is not a service failure. Concurrent requests share one load,
               // so a request that was not cancelled itself loads the tile again.
               if (attempt == 0 && !Thread.currentThread().isInterrupted()) {
                  continue;
               }
               return null;
            }
            this.failedUntil.put(key, System.nanoTime() + FAILURE_RETRY_NANOS);
            if (this.serviceFailureLogged.compareAndSet(false, true)) {
               Tellus.LOGGER.warn("Macrostrat geologic map tiles are currently unavailable; rock faces will use the bundled GLiM rock map", cause);
            } else {
               Tellus.LOGGER.debug("Failed to load Macrostrat tile {}", key, cause);
            }
            return null;
         }
      }
   }

   private MacrostratTile loadTile(TileKey key, LookupMode lookupMode) throws IOException {
      Path cachePath = this.cachePath(key);
      if (Files.isRegularFile(cachePath)) {
         try {
            byte[] payload = PmTilesSafety.decompress(
               readCachedTile(cachePath), PmTilesSafety.COMPRESSION_GZIP, MAX_RESPONSE_BYTES, "Cached Macrostrat tile"
            );
            return MacrostratTile.parse(payload, GRID_SIZE, SKIPPED_SOURCES::contains);
         } catch (IOException | RuntimeException error) {
            if (SourceInterrupts.isInterruption(error)) {
               throw error;
            }
            Tellus.LOGGER.debug("Discarding invalid cached Macrostrat tile {}", cachePath, error);
            Files.deleteIfExists(cachePath);
         }
      }

      if (lookupMode != LookupMode.BLOCKING || ManagedTerrainNetworkPolicy.isCacheOnly()) {
         throw new IOException("Macrostrat tile is not in the local cache");
      }

      long generation = TellusCacheRegistry.generation(TellusCacheDomain.GEOLOGY);
      byte[] payload = this.fetchTile(key);
      MacrostratTile tile = MacrostratTile.parse(payload, GRID_SIZE, SKIPPED_SOURCES::contains);
      try {
         TellusCacheFiles.writeIfCurrent(TellusCacheDomain.GEOLOGY, generation, cachePath, output -> {
            try (OutputStream gzip = new GZIPOutputStream(output)) {
               gzip.write(payload);
            }
         });
      } catch (IOException error) {
         Tellus.LOGGER.debug("Failed to cache Macrostrat tile {}", cachePath, error);
      }
      this.serviceFailureLogged.set(false);
      return tile;
   }

   private static byte[] readCachedTile(Path path) throws IOException {
      // An interrupt closes the file channel mid-read; a small local read finishes first instead.
      boolean interrupted = Thread.interrupted();
      try {
         return Files.readAllBytes(path);
      } finally {
         if (interrupted) {
            Thread.currentThread().interrupt();
         }
      }
   }

   /** The tile's uncompressed vector-tile bytes; Macrostrat answers an empty body where it has no maps. */
   private byte[] fetchTile(TileKey key) throws IOException {
      URI uri = URI.create(
         this.tileUrl.replace("{z}", Integer.toString(key.zoom())).replace("{x}", Integer.toString(key.x())).replace("{y}", Integer.toString(key.y()))
      );
      try {
         this.requests.acquire();
      } catch (InterruptedException error) {
         Thread.currentThread().interrupt();
         throw (IOException)new InterruptedIOException("Interrupted while waiting to download a Macrostrat tile").initCause(error);
      }
      try {
         IOException lastError = null;
         for (int attempt = 1; attempt <= FETCH_ATTEMPTS; attempt++) {
            try {
               HttpRequest request = HttpRequest.newBuilder(uri)
                  .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                  .header("Accept", "application/x-protobuf")
                  .header("Accept-Encoding", "gzip")
                  .header("User-Agent", "Tellus-Minecraft-Mod/" + MinecraftRelease.VERSION)
                  .GET()
                  .build();
               HttpResponse<byte[]> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
               if (response.statusCode() == 204) {
                  return new byte[0];
               }
               if (response.statusCode() != 200) {
                  throw new IOException("HTTP " + response.statusCode() + " from Macrostrat tile service");
               }
               byte[] body = response.body();
               if (body.length > MAX_RESPONSE_BYTES) {
                  throw new IOException("Unexpected Macrostrat tile size " + body.length);
               }
               boolean gzipped = response.headers().firstValue("Content-Encoding").map(value -> value.equalsIgnoreCase("gzip")).orElse(false);
               return gzipped && body.length > 0
                  ? PmTilesSafety.decompress(body, PmTilesSafety.COMPRESSION_GZIP, MAX_RESPONSE_BYTES, "Macrostrat tile")
                  : body;
            } catch (InterruptedException error) {
               Thread.currentThread().interrupt();
               throw (IOException)new InterruptedIOException("Interrupted while downloading a Macrostrat tile").initCause(error);
            } catch (IOException error) {
               if (SourceInterrupts.isInterruption(error)) {
                  throw error;
               }
               lastError = error;
            }
         }
         throw Objects.requireNonNullElseGet(lastError, () -> new IOException("Unable to download Macrostrat tile"));
      } finally {
         this.requests.release();
      }
   }

   private Path cachePath(TileKey key) {
      return this.cacheRoot.resolve(key.zoom() + "/" + key.x() + "/" + key.y() + ".mvt.gz");
   }

   private static LookupMode managedLookupMode() {
      return ManagedTerrainNetworkPolicy.isCacheOnly() ? LookupMode.LOCAL_ONLY : LookupMode.BLOCKING;
   }

   private static String configuredTileUrl() {
      String configured = System.getProperty("tellus.macrostrat.tileUrl");
      return configured == null || configured.isBlank() ? DEFAULT_TILE_URL : configured.trim();
   }

   private static int intProperty(String key, int fallback, int minimum, int maximum) {
      try {
         return Math.max(minimum, Math.min(maximum, Integer.parseInt(System.getProperty(key, Integer.toString(fallback)).trim())));
      } catch (NumberFormatException error) {
         return fallback;
      }
   }

   @Override
   public TellusCacheDomain cacheDomain() {
      return TellusCacheDomain.GEOLOGY;
   }

   @Override
   public void clearCache() {
      this.cache.invalidateAll();
      this.failedUntil.invalidateAll();
      this.serviceFailureLogged.set(false);
   }

   private enum LookupMode {
      BLOCKING,
      LOCAL_ONLY
   }

   private record TileKey(int zoom, int x, int y) {
   }

   private static final class SharedHolder {
      private static final MacrostratGeologySource INSTANCE = new MacrostratGeologySource();

      private SharedHolder() {
      }
   }
}
