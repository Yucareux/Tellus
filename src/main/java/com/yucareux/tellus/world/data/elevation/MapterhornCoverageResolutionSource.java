package com.yucareux.tellus.world.data.elevation;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.cache.TellusCacheDomain;
import com.yucareux.tellus.cache.TellusCacheFiles;
import com.yucareux.tellus.cache.TellusCacheRegistry;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.platform.TellusPlatform;
import com.yucareux.tellus.world.data.source.InputStreamSafety;
import com.yucareux.tellus.worldgen.WorldProjection;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.Feature;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.GeomType;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.Layer;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.Value;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves the native source resolution advertised by Mapterhorn for a location.
 *
 * <p>Mapterhorn publishes source coverage as vector tiles and source metadata in
 * {@code attribution.json}. The compact metadata snapshot is bundled with Tellus,
 * while the few vector tiles intersecting a preview are cached on demand.</p>
 */
final class MapterhornCoverageResolutionSource {
   private static final String COVERAGE_LAYER_NAME = "coverage";
   private static final String SOURCE_PROPERTY_NAME = "source";
   private static final String DEFAULT_CATALOG_RESOURCE = "/tellus/elevation/mapterhorn_source_resolutions.json";
   private static final String DEFAULT_COVERAGE_ENDPOINT = "https://single-archive-tiles.mapterhorn.com/coverage";
   private static final double WEB_MERCATOR_CIRCUMFERENCE_METERS = 40075016.68557849;
   private static final double MIN_LAT = -85.05112878;
   private static final double MAX_LAT = 85.05112878;
   private static final double MIN_LON = -180.0;
   private static final double MAX_LON = 180.0;
   private static final int TILE_SIZE = 512;
   private static final int DEFAULT_TILE_EXTENT = 4096;
   private static final int MIN_ZOOM = 0;
   private static final int MAX_ZOOM = 14;
   private static final int MAX_CATALOG_BYTES = 256 * 1024;
   private static final int MAX_TILE_BYTES = 4 * 1024 * 1024;
   private static final int MAX_CACHE_TILES = intProperty("tellus.mapterhorn.coverage.cacheTiles", 32, 4, 512);
   private static final int CONNECT_TIMEOUT_MS = intProperty("tellus.mapterhorn.coverage.connectTimeoutMs", 8000, 1000, 120000);
   private static final int READ_TIMEOUT_MS = intProperty("tellus.mapterhorn.coverage.readTimeoutMs", 8000, 1000, 180000);
   private static final int DOWNLOAD_ATTEMPTS = intProperty("tellus.mapterhorn.coverage.downloadAttempts", 2, 1, 5);
   private static final String COVERAGE_ENDPOINT = stringProperty("tellus.mapterhorn.coverage.endpoint", DEFAULT_COVERAGE_ENDPOINT);

   private final MapterhornCoverageResolutionSource.SourceCatalog catalog;
   private final Path cacheRoot;
   private final LoadingCache<MapterhornCoverageResolutionSource.TileKey, MapterhornCoverageResolutionSource.CoverageTile> cache;

   MapterhornCoverageResolutionSource() {
      this(loadBundledCatalog(), TellusPlatform.gameDir());
   }

   MapterhornCoverageResolutionSource(MapterhornCoverageResolutionSource.SourceCatalog catalog, Path gameDir) {
      this.catalog = Objects.requireNonNull(catalog, "catalog");
      this.cacheRoot = Objects.requireNonNull(gameDir, "gameDir")
         .resolve("tellus/cache/elevation-mapterhorn-coverage")
         .resolve(cacheNamespace(catalog.version()));
      this.cache = CacheBuilder.newBuilder().maximumSize(MAX_CACHE_TILES).build(new CacheLoader<>() {
         @Override
         public MapterhornCoverageResolutionSource.CoverageTile load(MapterhornCoverageResolutionSource.TileKey key) throws Exception {
            return MapterhornCoverageResolutionSource.this.loadTileBlocking(key);
         }
      });
   }

   double lookupResolutionMeters(
      double blockX, double blockZ, WorldProjection projection, double previewResolutionMeters
   ) {
      MapterhornCoverageResolutionSource.LookupMode mode = ManagedTerrainNetworkPolicy.isCacheOnly()
         ? MapterhornCoverageResolutionSource.LookupMode.LOCAL_ONLY
         : MapterhornCoverageResolutionSource.LookupMode.BLOCKING;
      return this.lookupResolutionMeters(blockX, blockZ, projection, previewResolutionMeters, mode);
   }

   double lookupResolutionMetersLocalOnly(
      double blockX, double blockZ, WorldProjection projection, double previewResolutionMeters
   ) {
      return this.lookupResolutionMeters(
         blockX, blockZ, projection, previewResolutionMeters, MapterhornCoverageResolutionSource.LookupMode.LOCAL_ONLY
      );
   }

   private double lookupResolutionMeters(
      double blockX,
      double blockZ,
      WorldProjection projection,
      double previewResolutionMeters,
      MapterhornCoverageResolutionSource.LookupMode lookupMode
   ) {
      double worldScale = projection.worldScale();
      if (!(worldScale > 0.0)) {
         return Double.NaN;
      }

      double lon = projection.blockXToLon(blockX);
      double lat = projection.blockZToLat(blockZ);
      double effectiveResolutionMeters = Double.isFinite(previewResolutionMeters) && previewResolutionMeters > 0.0
         ? Math.max(worldScale, previewResolutionMeters)
         : worldScale;
      int zoom = coverageZoom(effectiveResolutionMeters);
      MapterhornCoverageResolutionSource.TilePosition position = tilePosition(lon, lat, zoom);
      if (position == null) {
         return Double.NaN;
      }

      MapterhornCoverageResolutionSource.CoverageTile tile = this.getTile(position.key(), lookupMode);
      return tile == null || !tile.available()
         ? Double.NaN
         : tile.sample(position.localXFraction(), position.localYFraction());
   }

   private MapterhornCoverageResolutionSource.CoverageTile getTile(
      MapterhornCoverageResolutionSource.TileKey key, MapterhornCoverageResolutionSource.LookupMode lookupMode
   ) {
      MapterhornCoverageResolutionSource.CoverageTile cached = this.cache.getIfPresent(key);
      if (cached != null) {
         return cached;
      }
      MapterhornCoverageResolutionSource.CoverageTile local = this.loadTileLocalOnly(key);
      if (local != null) {
         MapterhornCoverageResolutionSource.CoverageTile raced = this.cache.asMap().putIfAbsent(key, local);
         return raced == null ? local : raced;
      }
      if (lookupMode == MapterhornCoverageResolutionSource.LookupMode.LOCAL_ONLY) {
         return null;
      }

      try {
         return this.cache.get(key);
      } catch (Exception error) {
         if (isInterrupted(error)) {
            Thread.currentThread().interrupt();
         } else {
            Tellus.LOGGER.debug("Failed to load Mapterhorn coverage tile {}", key, error);
         }
         this.cache.put(key, MapterhornCoverageResolutionSource.CoverageTile.unavailable());
         return null;
      }
   }

   private MapterhornCoverageResolutionSource.CoverageTile loadTileBlocking(
      MapterhornCoverageResolutionSource.TileKey key
   ) throws IOException {
      MapterhornCoverageResolutionSource.CoverageTile local = this.loadTileLocalOnly(key);
      if (local != null) {
         return local;
      }

      byte[] payload = this.downloadTileWithRetry(key);
      MapterhornCoverageResolutionSource.CoverageTile tile = decodeCoverageTile(payload, this.catalog.sourceResolutions());
      long generation = TellusCacheRegistry.generation(TellusCacheDomain.TERRAIN);
      if (!TellusCacheFiles.writeBytesIfCurrent(TellusCacheDomain.TERRAIN, generation, this.cachePath(key), payload)) {
         throw new IOException("Discarded stale Mapterhorn coverage cache write for " + key);
      }
      return tile;
   }

   private MapterhornCoverageResolutionSource.CoverageTile loadTileLocalOnly(
      MapterhornCoverageResolutionSource.TileKey key
   ) {
      Path path = this.cachePath(key);
      if (!Files.isRegularFile(path)) {
         return null;
      }

      try (InputStream input = Files.newInputStream(path)) {
         byte[] payload = InputStreamSafety.readAllBytes(input, MAX_TILE_BYTES, "Cached Mapterhorn coverage tile");
         return decodeCoverageTile(payload, this.catalog.sourceResolutions());
      } catch (RuntimeException | IOException error) {
         Tellus.LOGGER.debug("Invalid cached Mapterhorn coverage tile {}, refetching", path, error);
         try {
            Files.deleteIfExists(path);
         } catch (IOException deleteError) {
            Tellus.LOGGER.debug("Failed to delete invalid Mapterhorn coverage tile {}", path, deleteError);
         }
         return null;
      }
   }

   private byte[] downloadTileWithRetry(MapterhornCoverageResolutionSource.TileKey key) throws IOException {
      IOException lastFailure = null;
      for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
         try {
            return downloadTile(key);
         } catch (IOException error) {
            if (isInterrupted(error)) {
               Thread.currentThread().interrupt();
               throw error;
            }
            lastFailure = error;
         }
      }
      throw new IOException("Mapterhorn coverage download failed for " + key, lastFailure);
   }

   private static byte[] downloadTile(MapterhornCoverageResolutionSource.TileKey key) throws IOException {
      URI uri = URI.create(
         String.format(Locale.ROOT, "%s/%d/%d/%d.mvt", COVERAGE_ENDPOINT, key.zoom(), key.x(), key.y())
      );
      HttpURLConnection connection = (HttpURLConnection)uri.toURL().openConnection();
      connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
      connection.setReadTimeout(READ_TIMEOUT_MS);
      connection.setRequestProperty("Accept", "application/x-protobuf, application/vnd.mapbox-vector-tile");
      connection.setRequestProperty("User-Agent", "Tellus-Mapterhorn-Coverage/1.0");
      try {
         int status = connection.getResponseCode();
         if (status == HttpURLConnection.HTTP_NOT_FOUND || status == HttpURLConnection.HTTP_NO_CONTENT) {
            return new byte[0];
         }
         if (status != HttpURLConnection.HTTP_OK) {
            throw new IOException("Mapterhorn coverage request returned HTTP " + status + " for " + key);
         }
         long contentLength = connection.getContentLengthLong();
         if (contentLength > MAX_TILE_BYTES) {
            throw new IOException("Mapterhorn coverage tile exceeds the safety limit for " + key);
         }
         try (InputStream input = Objects.requireNonNull(connection.getInputStream(), "mapterhornCoverageResponse")) {
            return InputStreamSafety.readAllBytes(input, MAX_TILE_BYTES, "Mapterhorn coverage tile");
         }
      } finally {
         connection.disconnect();
      }
   }

   private Path cachePath(MapterhornCoverageResolutionSource.TileKey key) {
      return this.cacheRoot.resolve(Integer.toString(key.zoom())).resolve(key.x() + "_" + key.y() + ".mvt");
   }

   void retryMissingTiles() {
      this.cache.asMap().entrySet().removeIf(entry -> !entry.getValue().available());
   }

   void clearCache() {
      this.cache.invalidateAll();
      this.cache.cleanUp();
   }

   static int coverageZoom(double resolutionMeters) {
      if (!(resolutionMeters > 0.0) || !Double.isFinite(resolutionMeters)) {
         return MIN_ZOOM;
      }
      double zoom = Math.log(WEB_MERCATOR_CIRCUMFERENCE_METERS / (TILE_SIZE * resolutionMeters)) / Math.log(2.0);
      return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, (int)Math.round(zoom)));
   }

   static MapterhornCoverageResolutionSource.CoverageTile decodeCoverageTile(
      byte[] payload, Map<String, Double> sourceResolutions
   ) throws IOException {
      Objects.requireNonNull(sourceResolutions, "sourceResolutions");
      if (payload == null || payload.length == 0) {
         return MapterhornCoverageResolutionSource.CoverageTile.empty();
      }

      Tile tile;
      try {
         tile = Tile.parseFrom(payload);
      } catch (Exception error) {
         throw new IOException("Failed to decode Mapterhorn coverage MVT", error);
      }

      int extent = DEFAULT_TILE_EXTENT;
      List<MapterhornCoverageResolutionSource.CoverageFeature> features = new ArrayList<>();
      for (Layer layer : tile.getLayersList()) {
         if (!COVERAGE_LAYER_NAME.equals(layer.getName())) {
            continue;
         }
         extent = layer.hasExtent() && layer.getExtent() > 0 ? layer.getExtent() : DEFAULT_TILE_EXTENT;
         for (Feature feature : layer.getFeaturesList()) {
            if (feature.getType() != GeomType.POLYGON) {
               continue;
            }
            String source = tagString(feature, layer, SOURCE_PROPERTY_NAME);
            Double resolutionMeters = sourceResolutions.get(source);
            if (resolutionMeters == null || !(resolutionMeters > 0.0) || !Double.isFinite(resolutionMeters)) {
               continue;
            }
            List<MapterhornCoverageResolutionSource.Ring> rings = decodePolygonRings(feature.getGeometryList());
            if (!rings.isEmpty()) {
               features.add(new MapterhornCoverageResolutionSource.CoverageFeature(resolutionMeters, List.copyOf(rings)));
            }
         }
      }
      return new MapterhornCoverageResolutionSource.CoverageTile(true, extent, List.copyOf(features));
   }

   static MapterhornCoverageResolutionSource.SourceCatalog readSourceCatalog(InputStream input) throws IOException {
      Objects.requireNonNull(input, "input");
      byte[] bytes = InputStreamSafety.readAllBytes(input, MAX_CATALOG_BYTES, "Mapterhorn source resolution catalog");
      JsonObject root;
      try {
         root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
      } catch (RuntimeException error) {
         throw new IOException("Invalid Mapterhorn source resolution catalog", error);
      }

      JsonElement versionElement = root.get("version");
      JsonObject sources = root.getAsJsonObject("sources");
      if (versionElement == null || !versionElement.isJsonPrimitive() || sources == null) {
         throw new IOException("Mapterhorn source resolution catalog is missing required fields");
      }

      Map<String, Double> resolutions = new HashMap<>();
      for (Map.Entry<String, JsonElement> entry : sources.entrySet()) {
         double resolution;
         try {
            resolution = entry.getValue().getAsDouble();
         } catch (RuntimeException error) {
            throw new IOException("Invalid Mapterhorn resolution for source " + entry.getKey(), error);
         }
         if (entry.getKey().isBlank() || !(resolution > 0.0) || !Double.isFinite(resolution)) {
            throw new IOException("Invalid Mapterhorn resolution for source " + entry.getKey());
         }
         resolutions.put(entry.getKey(), resolution);
      }
      if (resolutions.isEmpty() || Math.abs(resolutions.getOrDefault("glo30", Double.NaN) - 30.0) > 1.0E-9) {
         throw new IOException("Mapterhorn source resolution catalog does not contain the 30 m global source");
      }
      return new MapterhornCoverageResolutionSource.SourceCatalog(versionElement.getAsString(), Map.copyOf(resolutions));
   }

   private static MapterhornCoverageResolutionSource.SourceCatalog loadBundledCatalog() {
      try (InputStream input = MapterhornCoverageResolutionSource.class.getResourceAsStream(DEFAULT_CATALOG_RESOURCE)) {
         if (input == null) {
            throw new IOException("Missing resource " + DEFAULT_CATALOG_RESOURCE);
         }
         MapterhornCoverageResolutionSource.SourceCatalog catalog = readSourceCatalog(input);
         Tellus.LOGGER.info(
            "Loaded Mapterhorn {} source-resolution catalog ({} sources).",
            catalog.version(),
            catalog.sourceResolutions().size()
         );
         return catalog;
      } catch (IOException error) {
         Tellus.LOGGER.warn("Failed to load the bundled Mapterhorn source-resolution catalog.", error);
         return new MapterhornCoverageResolutionSource.SourceCatalog("fallback", Map.of("glo30", 30.0));
      }
   }

   static MapterhornCoverageResolutionSource.TilePosition tilePosition(double lon, double lat, int zoom) {
      if (!Double.isFinite(lon)
         || !Double.isFinite(lat)
         || lon < MIN_LON
         || lon > MAX_LON
         || lat < MIN_LAT
         || lat > MAX_LAT) {
         return null;
      }

      double n = Math.scalb(1.0, zoom);
      double x = (lon + 180.0) / 360.0 * n;
      double latRadians = Math.toRadians(lat);
      double y = (1.0 - Math.log(Math.tan(latRadians) + 1.0 / Math.cos(latRadians)) / Math.PI) * 0.5 * n;
      x = Math.max(0.0, Math.min(Math.nextDown(n), x));
      y = Math.max(0.0, Math.min(Math.nextDown(n), y));
      int tileX = (int)Math.floor(x);
      int tileY = (int)Math.floor(y);
      return new MapterhornCoverageResolutionSource.TilePosition(
         new MapterhornCoverageResolutionSource.TileKey(zoom, tileX, tileY), x - tileX, y - tileY
      );
   }

   private static List<MapterhornCoverageResolutionSource.Ring> decodePolygonRings(List<Integer> geometry) {
      if (geometry == null || geometry.isEmpty()) {
         return List.of();
      }

      List<MapterhornCoverageResolutionSource.Ring> rings = new ArrayList<>();
      List<Double> currentX = null;
      List<Double> currentY = null;
      int cursorX = 0;
      int cursorY = 0;
      int cursor = 0;
      while (cursor < geometry.size()) {
         int commandAndCount = geometry.get(cursor++);
         int command = commandAndCount & 7;
         int count = commandAndCount >>> 3;
         if (count <= 0) {
            continue;
         }
         if (command == 1 || command == 2) {
            for (int i = 0; i < count; i++) {
               if (cursor + 1 >= geometry.size()) {
                  addRing(rings, currentX, currentY);
                  return rings;
               }
               cursorX += zigZagDecode(geometry.get(cursor++));
               cursorY += zigZagDecode(geometry.get(cursor++));
               if (command == 1) {
                  addRing(rings, currentX, currentY);
                  currentX = new ArrayList<>();
                  currentY = new ArrayList<>();
               } else if (currentX == null) {
                  currentX = new ArrayList<>();
                  currentY = new ArrayList<>();
               }
               currentX.add((double)cursorX);
               currentY.add((double)cursorY);
            }
         } else if (command == 7) {
            addRing(rings, currentX, currentY);
            currentX = null;
            currentY = null;
         } else {
            break;
         }
      }
      addRing(rings, currentX, currentY);
      return rings;
   }

   private static void addRing(
      List<MapterhornCoverageResolutionSource.Ring> rings, List<Double> xs, List<Double> ys
   ) {
      if (xs == null || ys == null || xs.size() != ys.size() || xs.size() < 3) {
         return;
      }
      double[] ringX = new double[xs.size()];
      double[] ringY = new double[ys.size()];
      for (int i = 0; i < xs.size(); i++) {
         ringX[i] = xs.get(i);
         ringY[i] = ys.get(i);
      }
      rings.add(new MapterhornCoverageResolutionSource.Ring(ringX, ringY));
   }

   private static int zigZagDecode(int encoded) {
      return encoded >>> 1 ^ -(encoded & 1);
   }

   private static String tagString(Feature feature, Layer layer, String requestedKey) {
      List<Integer> tags = feature.getTagsList();
      for (int i = 0; i + 1 < tags.size(); i += 2) {
         int keyIndex = tags.get(i);
         int valueIndex = tags.get(i + 1);
         if (keyIndex >= 0
            && keyIndex < layer.getKeysCount()
            && valueIndex >= 0
            && valueIndex < layer.getValuesCount()
            && requestedKey.equals(layer.getKeys(keyIndex))) {
            Value value = layer.getValues(valueIndex);
            return value.hasStringValue() ? value.getStringValue() : null;
         }
      }
      return null;
   }

   private static boolean isInterrupted(Throwable error) {
      Throwable current = error;
      while (current != null) {
         if (current instanceof InterruptedException || current instanceof InterruptedIOException) {
            return true;
         }
         current = current.getCause();
      }
      return false;
   }

   private static String cacheNamespace(String version) {
      String sanitized = version == null ? "unknown" : version.replaceAll("[^A-Za-z0-9._-]", "_");
      return sanitized.isBlank() ? "unknown" : sanitized;
   }

   private static int intProperty(String key, int fallback, int minimum, int maximum) {
      try {
         return Math.max(minimum, Math.min(maximum, Integer.getInteger(key, fallback)));
      } catch (RuntimeException ignored) {
         return fallback;
      }
   }

   private static String stringProperty(String key, String fallback) {
      String value = System.getProperty(key);
      if (value == null || value.isBlank()) {
         return fallback;
      }
      String trimmed = value.trim();
      return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
   }

   record SourceCatalog(String version, Map<String, Double> sourceResolutions) {
      SourceCatalog {
         version = Objects.requireNonNull(version, "version");
         sourceResolutions = Map.copyOf(sourceResolutions);
      }
   }

   record CoverageTile(boolean available, int extent, List<MapterhornCoverageResolutionSource.CoverageFeature> features) {
      CoverageTile {
         features = List.copyOf(features);
      }

      private static MapterhornCoverageResolutionSource.CoverageTile empty() {
         return new MapterhornCoverageResolutionSource.CoverageTile(true, DEFAULT_TILE_EXTENT, List.of());
      }

      private static MapterhornCoverageResolutionSource.CoverageTile unavailable() {
         return new MapterhornCoverageResolutionSource.CoverageTile(false, DEFAULT_TILE_EXTENT, List.of());
      }

      double sample(double localXFraction, double localYFraction) {
         double x = localXFraction * this.extent;
         double y = localYFraction * this.extent;
         double bestResolution = Double.POSITIVE_INFINITY;
         for (MapterhornCoverageResolutionSource.CoverageFeature feature : this.features) {
            if (feature.resolutionMeters() < bestResolution && feature.contains(x, y)) {
               bestResolution = feature.resolutionMeters();
            }
         }
         return Double.isFinite(bestResolution) ? bestResolution : Double.NaN;
      }
   }

   private record CoverageFeature(double resolutionMeters, List<MapterhornCoverageResolutionSource.Ring> rings) {
      private boolean contains(double x, double y) {
         boolean inside = false;
         for (MapterhornCoverageResolutionSource.Ring ring : this.rings) {
            if (ring.contains(x, y)) {
               inside = !inside;
            }
         }
         return inside;
      }
   }

   private record Ring(double[] xs, double[] ys) {
      private boolean contains(double x, double y) {
         boolean inside = false;
         for (int i = 0, previous = this.xs.length - 1; i < this.xs.length; previous = i++) {
            double currentY = this.ys[i];
            double previousY = this.ys[previous];
            if ((currentY > y) != (previousY > y)) {
               double edgeX = (this.xs[previous] - this.xs[i]) * (y - currentY) / (previousY - currentY) + this.xs[i];
               if (x < edgeX) {
                  inside = !inside;
               }
            }
         }
         return inside;
      }
   }

   record TileKey(int zoom, int x, int y) {
   }

   record TilePosition(
      MapterhornCoverageResolutionSource.TileKey key, double localXFraction, double localYFraction
   ) {
   }

   private enum LookupMode {
      BLOCKING,
      LOCAL_ONLY
   }
}
