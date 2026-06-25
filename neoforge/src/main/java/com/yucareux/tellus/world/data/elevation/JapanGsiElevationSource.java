package com.yucareux.tellus.world.data.elevation;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.cache.TellusCacheDomain;
import com.yucareux.tellus.cache.TellusCacheHandle;
import com.yucareux.tellus.cache.TellusCacheRegistry;
import com.yucareux.tellus.world.data.source.DownloadProgressReporter;
import com.yucareux.tellus.worldgen.EarthProjection;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import javax.imageio.ImageIO;
import net.neoforged.fml.loading.FMLPaths;
import net.minecraft.util.Mth;

public final class JapanGsiElevationSource implements TellusCacheHandle {
   private static final String BASE_ENDPOINT = "https://cyberjapandata.gsi.go.jp/xyz";
   private static final int HTTP_CONNECT_TIMEOUT = 8000;
   private static final int HTTP_READ_TIMEOUT = 8000;
   private static final String HTTP_USER_AGENT = "Tellus/1.0 (Minecraft Mod)";
   private static final int TILE_SIZE = 256;
   private static final int MIN_ZOOM = 1;
   private static final double EQUATOR_CIRCUMFERENCE = 4.0075017E7;
   private static final double MIN_LAT = -85.05112878;
   private static final double MAX_LAT = 85.05112878;
   private static final double MIN_LON = -180.0;
   private static final double MAX_LON = 180.0;
   private static final int GSI_NO_DATA_R = 128;
   private static final int GSI_NO_DATA_G = 0;
   private static final int GSI_NO_DATA_B = 0;
   private static final double GSI_UNIT_METERS = 0.01;
   private static final int MAX_CACHE_TILES = intProperty("tellus.japangsi.cacheTiles", 256);
   private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("tellus.japangsi.enabled", "true"));
   private static final JapanGsiElevationSource.TileRecord MISSING_TILE = new JapanGsiElevationSource.TileRecord(null, true);
   private static final JapanGsiElevationSource.TileLayer[] ALL_LAYERS = JapanGsiElevationSource.TileLayer.values();
   private static final JapanGsiElevationSource.TileLayer[] MEDIUM_RES_LAYERS = new JapanGsiElevationSource.TileLayer[]{
      JapanGsiElevationSource.TileLayer.DEM5A,
      JapanGsiElevationSource.TileLayer.DEM5B,
      JapanGsiElevationSource.TileLayer.DEM5C,
      JapanGsiElevationSource.TileLayer.DEM10B
   };
   private static final JapanGsiElevationSource.TileLayer[] COARSE_LAYERS = new JapanGsiElevationSource.TileLayer[]{JapanGsiElevationSource.TileLayer.DEM10B};
   private final Path cacheRoot;
   private final LoadingCache<JapanGsiElevationSource.TileKey, JapanGsiElevationSource.TileRecord> tileCache;

   public JapanGsiElevationSource() {
      this.cacheRoot = FMLPaths.GAMEDIR.get().resolve("tellus/cache/elevation-japangsi");
      this.tileCache = CacheBuilder.newBuilder().maximumSize(MAX_CACHE_TILES).build(new CacheLoader<JapanGsiElevationSource.TileKey, JapanGsiElevationSource.TileRecord>() {
         public JapanGsiElevationSource.TileRecord load(JapanGsiElevationSource.TileKey key) throws Exception {
            return JapanGsiElevationSource.this.loadTile(key);
         }
      });
      TellusCacheRegistry.register(this);
   }

   public boolean isLikelyInCoverage(double lat, double lon) {
      return ENABLED && isInCoverageBox(lat, lon);
   }

   public JapanGsiElevationSource.Sample sample(double blockX, double blockZ, double worldScale) {
      return this.sample(blockX, blockZ, worldScale, Double.NaN);
   }

   public JapanGsiElevationSource.Sample sample(double blockX, double blockZ, double worldScale, double targetResolutionMeters) {
      if (!ENABLED || worldScale <= 0.0) {
         return JapanGsiElevationSource.Sample.none();
      } else {
         JapanGsiElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
         if (latLon == null || !this.isLikelyInCoverage(latLon.lat(), latLon.lon())) {
            return JapanGsiElevationSource.Sample.none();
         } else {
            double sampleResolutionMeters = resolveSampleResolutionMeters(worldScale, targetResolutionMeters);

            for (JapanGsiElevationSource.TileLayer layer : ALL_LAYERS) {
               double sample = this.sampleLayer(layer, latLon.lat(), latLon.lon(), sampleResolutionMeters);
               if (Double.isFinite(sample)) {
                  return new JapanGsiElevationSource.Sample(sample, TellusElevationSource.DemUsage.JAPANGSI, layer.nominalResolutionMeters());
               }
            }

            return JapanGsiElevationSource.Sample.none();
         }
      }
   }

   public JapanGsiElevationSource.Sample sampleLocalOnly(double blockX, double blockZ, double worldScale, double targetResolutionMeters) {
      if (!ENABLED || worldScale <= 0.0) {
         return JapanGsiElevationSource.Sample.none();
      } else {
         JapanGsiElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
         if (latLon == null || !this.isLikelyInCoverage(latLon.lat(), latLon.lon())) {
            return JapanGsiElevationSource.Sample.none();
         } else {
            double sampleResolutionMeters = resolveSampleResolutionMeters(worldScale, targetResolutionMeters);

            for (JapanGsiElevationSource.TileLayer layer : ALL_LAYERS) {
               double sample = this.sampleLayerLocalOnly(layer, latLon.lat(), latLon.lon(), sampleResolutionMeters);
               if (Double.isFinite(sample)) {
                  return new JapanGsiElevationSource.Sample(sample, TellusElevationSource.DemUsage.JAPANGSI, layer.nominalResolutionMeters());
               }
            }

            return JapanGsiElevationSource.Sample.none();
         }
      }
   }

   public void prefetchTiles(double blockX, double blockZ, double worldScale, int radius) {
      this.prefetchTiles(blockX, blockZ, worldScale, radius, Double.NaN);
   }

   public void prefetchTiles(double blockX, double blockZ, double worldScale, int radius, double targetResolutionMeters) {
      if (ENABLED && !(worldScale <= 0.0) && radius >= 0) {
         JapanGsiElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
         if (latLon != null && this.isLikelyInCoverage(latLon.lat(), latLon.lon())) {
            double sampleResolutionMeters = resolveSampleResolutionMeters(worldScale, targetResolutionMeters);
            int tileRadius = Math.max(0, radius);

            for (JapanGsiElevationSource.TileLayer layer : prefetchLayers(sampleResolutionMeters)) {
               JapanGsiElevationSource.TileCoordinate center = tileCoordinateForLatLon(latLon.lat(), latLon.lon(), selectZoom(sampleResolutionMeters, layer.maxZoom()));
               if (center != null) {
                  this.prefetchNeighborhood(new JapanGsiElevationSource.TileKey(layer, center.zoom(), center.tileX(), center.tileY()), tileRadius);
               }
            }
         }
      }
   }

   private void prefetchNeighborhood(JapanGsiElevationSource.TileKey center, int tileRadius) {
      int tilesPerAxis = 1 << center.zoom();

      for (int dy = -tileRadius; dy <= tileRadius; dy++) {
         for (int dx = -tileRadius; dx <= tileRadius; dx++) {
            int tileX = center.x() + dx;
            int tileY = center.y() + dy;
            if (tileX >= 0 && tileY >= 0 && tileX < tilesPerAxis && tileY < tilesPerAxis) {
               this.prefetchTile(new JapanGsiElevationSource.TileKey(center.layer(), center.zoom(), tileX, tileY));
            }
         }
      }
   }

   private void prefetchTile(JapanGsiElevationSource.TileKey key) {
      if (this.tileCache.getIfPresent(key) == null) {
         try {
            this.tileCache.get(key);
         } catch (ExecutionException error) {
            Tellus.LOGGER.debug("Failed to prefetch Japan GSI tile {}", key, error);
         }
      }
   }

   private double sampleLayer(JapanGsiElevationSource.TileLayer layer, double lat, double lon, double sampleResolutionMeters) {
      JapanGsiElevationSource.TileCoordinate coord = tileCoordinateForLatLon(lat, lon, selectZoom(sampleResolutionMeters, layer.maxZoom()));
      if (coord == null) {
         return Double.NaN;
      } else {
         FloatRaster raster = this.getTile(new JapanGsiElevationSource.TileKey(layer, coord.zoom(), coord.tileX(), coord.tileY()));
         if (raster == null) {
            return Double.NaN;
         } else {
            return this.sampleBilinearAcrossTiles(coord, raster, layer);
         }
      }
   }

   private double sampleLayerLocalOnly(JapanGsiElevationSource.TileLayer layer, double lat, double lon, double sampleResolutionMeters) {
      JapanGsiElevationSource.TileCoordinate coord = tileCoordinateForLatLon(lat, lon, selectZoom(sampleResolutionMeters, layer.maxZoom()));
      if (coord == null) {
         return Double.NaN;
      } else {
         FloatRaster raster = this.getTileLocalOnly(new JapanGsiElevationSource.TileKey(layer, coord.zoom(), coord.tileX(), coord.tileY()));
         return raster == null ? Double.NaN : this.sampleBilinearAcrossTilesLocalOnly(coord, raster, layer);
      }
   }

   private FloatRaster getTile(JapanGsiElevationSource.TileKey key) {
      try {
         JapanGsiElevationSource.TileRecord record = this.tileCache.get(key);
         return record.missing() ? null : record.raster();
      } catch (ExecutionException error) {
         Tellus.LOGGER.debug("Failed to load Japan GSI tile {}", key, error);
         return null;
      }
   }

   private FloatRaster getTileLocalOnly(JapanGsiElevationSource.TileKey key) {
      JapanGsiElevationSource.TileRecord cached = this.tileCache.getIfPresent(key);
      if (cached != null) {
         return cached.missing() ? null : cached.raster();
      } else {
         Path missingMarker = key.missingMarkerPath(this.cacheRoot);
         if (Files.exists(missingMarker)) {
            this.tileCache.put(key, MISSING_TILE);
            return null;
         } else {
            Path cachePath = key.cachePath(this.cacheRoot);
            if (!Files.exists(cachePath)) {
               return null;
            } else {
               try (InputStream input = Files.newInputStream(cachePath)) {
                  JapanGsiElevationSource.TileRecord record = new JapanGsiElevationSource.TileRecord(readPngRaster(input), false);
                  JapanGsiElevationSource.TileRecord raced = this.tileCache.asMap().putIfAbsent(key, record);
                  JapanGsiElevationSource.TileRecord resolved = raced != null ? raced : record;
                  return resolved.missing() ? null : resolved.raster();
               } catch (IOException error) {
                  Tellus.LOGGER.debug("Failed to load cached Japan GSI tile {}", key, error);
                  return null;
               }
            }
         }
      }
   }

   private JapanGsiElevationSource.TileRecord loadTile(JapanGsiElevationSource.TileKey key) throws Exception {
      Path cachePath = key.cachePath(this.cacheRoot);
      if (Files.exists(cachePath)) {
         try {
            try (InputStream input = Files.newInputStream(cachePath)) {
               return new JapanGsiElevationSource.TileRecord(readPngRaster(input), false);
            }
         } catch (IOException error) {
            Files.deleteIfExists(cachePath);
         }
      }

      Path missingMarker = key.missingMarkerPath(this.cacheRoot);
      if (Files.exists(missingMarker)) {
         return MISSING_TILE;
      } else {
         byte[] data;
         try {
            data = this.downloadTile(key);
         } catch (JapanGsiElevationSource.MissingTileException error) {
            this.writeMissingMarker(missingMarker);
            return MISSING_TILE;
         }

         this.cacheTile(cachePath, data);

         try (InputStream input = new ByteArrayInputStream(data)) {
            return new JapanGsiElevationSource.TileRecord(readPngRaster(input), false);
         } catch (IOException error) {
            Files.deleteIfExists(cachePath);
            throw error;
         }
      }
   }

   private byte[] downloadTile(JapanGsiElevationSource.TileKey key) throws IOException {
      HttpURLConnection connection = (HttpURLConnection)URI.create(key.url()).toURL().openConnection();
      connection.setConnectTimeout(HTTP_CONNECT_TIMEOUT);
      connection.setReadTimeout(HTTP_READ_TIMEOUT);
      connection.setRequestProperty("User-Agent", HTTP_USER_AGENT);

      try {
         int status = connection.getResponseCode();
         if (status == 404) {
            throw new JapanGsiElevationSource.MissingTileException();
         } else if (status != 200) {
            throw new IOException("Unexpected Japan GSI status " + status + " for " + key.url());
         } else {
            DownloadProgressReporter.requestStarted(Math.max(0L, connection.getContentLengthLong()));

            try (InputStream input = Objects.requireNonNull(connection.getInputStream(), "japanGsiResponse")) {
               return DownloadProgressReporter.readAllBytesWithProgress(input);
            } finally {
               DownloadProgressReporter.requestFinished();
            }
         }
      } finally {
         connection.disconnect();
      }
   }

   private void cacheTile(Path cachePath, byte[] data) throws IOException {
      Files.createDirectories(cachePath.getParent());
      Path tempPath = cachePath.resolveSibling(cachePath.getFileName() + ".tmp");
      Files.write(tempPath, data);

      try {
         Files.move(tempPath, cachePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException error) {
         Files.move(tempPath, cachePath, StandardCopyOption.REPLACE_EXISTING);
      }
   }

   private void writeMissingMarker(Path missingMarker) {
      try {
         Files.createDirectories(missingMarker.getParent());
         if (!Files.exists(missingMarker)) {
            Files.writeString(missingMarker, "missing");
         }
      } catch (IOException error) {
         Tellus.LOGGER.debug("Failed to persist Japan GSI missing marker {}", missingMarker, error);
      }
   }

   private double sampleBilinearAcrossTiles(JapanGsiElevationSource.TileCoordinate coord, FloatRaster baseRaster, JapanGsiElevationSource.TileLayer layer) {
      int tilesPerAxis = 1 << coord.zoom();
      int maxPixel = tilesPerAxis * TILE_SIZE - 1;
      double clampedX = Mth.clamp(coord.globalPixelX(), 0.0, maxPixel);
      double clampedY = Mth.clamp(coord.globalPixelY(), 0.0, maxPixel);
      int x0 = Mth.floor(clampedX);
      int y0 = Mth.floor(clampedY);
      int x1 = Math.min(x0 + 1, maxPixel);
      int y1 = Math.min(y0 + 1, maxPixel);
      double dx = clampedX - x0;
      double dy = clampedY - y0;
      float v00 = this.samplePixel(layer, coord.zoom(), x0, y0, coord.tileX(), coord.tileY(), baseRaster);
      float v10 = this.samplePixel(layer, coord.zoom(), x1, y0, coord.tileX(), coord.tileY(), baseRaster);
      float v01 = this.samplePixel(layer, coord.zoom(), x0, y1, coord.tileX(), coord.tileY(), baseRaster);
      float v11 = this.samplePixel(layer, coord.zoom(), x1, y1, coord.tileX(), coord.tileY(), baseRaster);
      return blendFiniteSamples(v00, v10, v01, v11, dx, dy);
   }

   private double sampleBilinearAcrossTilesLocalOnly(
      JapanGsiElevationSource.TileCoordinate coord, FloatRaster baseRaster, JapanGsiElevationSource.TileLayer layer
   ) {
      int tilesPerAxis = 1 << coord.zoom();
      int maxPixel = tilesPerAxis * TILE_SIZE - 1;
      double clampedX = Mth.clamp(coord.globalPixelX(), 0.0, maxPixel);
      double clampedY = Mth.clamp(coord.globalPixelY(), 0.0, maxPixel);
      int x0 = Mth.floor(clampedX);
      int y0 = Mth.floor(clampedY);
      int x1 = Math.min(x0 + 1, maxPixel);
      int y1 = Math.min(y0 + 1, maxPixel);
      double dx = clampedX - x0;
      double dy = clampedY - y0;
      float v00 = this.samplePixelLocalOnly(layer, coord.zoom(), x0, y0, coord.tileX(), coord.tileY(), baseRaster);
      float v10 = this.samplePixelLocalOnly(layer, coord.zoom(), x1, y0, coord.tileX(), coord.tileY(), baseRaster);
      float v01 = this.samplePixelLocalOnly(layer, coord.zoom(), x0, y1, coord.tileX(), coord.tileY(), baseRaster);
      float v11 = this.samplePixelLocalOnly(layer, coord.zoom(), x1, y1, coord.tileX(), coord.tileY(), baseRaster);
      return blendFiniteSamples(v00, v10, v01, v11, dx, dy);
   }

   private float samplePixel(
      JapanGsiElevationSource.TileLayer layer, int zoom, int pixelX, int pixelY, int baseTileX, int baseTileY, FloatRaster baseRaster
   ) {
      int tileX = Math.floorDiv(pixelX, TILE_SIZE);
      int tileY = Math.floorDiv(pixelY, TILE_SIZE);
      int tilesPerAxis = 1 << zoom;
      if (tileX < 0 || tileY < 0 || tileX >= tilesPerAxis || tileY >= tilesPerAxis) {
         return Float.NaN;
      } else {
         FloatRaster raster = tileX == baseTileX && tileY == baseTileY ? baseRaster : this.getTile(new JapanGsiElevationSource.TileKey(layer, zoom, tileX, tileY));
         if (raster == null) {
            return Float.NaN;
         } else {
            int localX = pixelX - tileX * TILE_SIZE;
            int localY = pixelY - tileY * TILE_SIZE;
            return raster.get(localX, localY);
         }
      }
   }

   private float samplePixelLocalOnly(
      JapanGsiElevationSource.TileLayer layer, int zoom, int pixelX, int pixelY, int baseTileX, int baseTileY, FloatRaster baseRaster
   ) {
      int tileX = Math.floorDiv(pixelX, TILE_SIZE);
      int tileY = Math.floorDiv(pixelY, TILE_SIZE);
      int tilesPerAxis = 1 << zoom;
      if (tileX < 0 || tileY < 0 || tileX >= tilesPerAxis || tileY >= tilesPerAxis) {
         return Float.NaN;
      } else {
         FloatRaster raster = tileX == baseTileX && tileY == baseTileY
            ? baseRaster
            : this.getTileLocalOnly(new JapanGsiElevationSource.TileKey(layer, zoom, tileX, tileY));
         if (raster == null) {
            return Float.NaN;
         } else {
            int localX = pixelX - tileX * TILE_SIZE;
            int localY = pixelY - tileY * TILE_SIZE;
            return raster.get(localX, localY);
         }
      }
   }

   private static double blendFiniteSamples(float v00, float v10, float v01, float v11, double dx, double dy) {
      double w00 = (1.0 - dx) * (1.0 - dy);
      double w10 = dx * (1.0 - dy);
      double w01 = (1.0 - dx) * dy;
      double w11 = dx * dy;
      double sum = 0.0;
      double weight = 0.0;
      if (Float.isFinite(v00)) {
         sum += v00 * w00;
         weight += w00;
      }

      if (Float.isFinite(v10)) {
         sum += v10 * w10;
         weight += w10;
      }

      if (Float.isFinite(v01)) {
         sum += v01 * w01;
         weight += w01;
      }

      if (Float.isFinite(v11)) {
         sum += v11 * w11;
         weight += w11;
      }

      return weight > 0.0 ? sum / weight : Double.NaN;
   }

   private static FloatRaster readPngRaster(InputStream input) throws IOException {
      BufferedImage image = ImageIO.read(input);
      if (image == null) {
         throw new IOException("Invalid Japan GSI PNG tile");
      } else {
         int width = image.getWidth();
         int height = image.getHeight();
         FloatRaster raster = FloatRaster.create(width, height);

         for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
               int argb = image.getRGB(x, y);
               int red = argb >> 16 & 0xFF;
               int green = argb >> 8 & 0xFF;
               int blue = argb & 0xFF;
               if (red == GSI_NO_DATA_R && green == GSI_NO_DATA_G && blue == GSI_NO_DATA_B) {
                  raster.set(x, y, Float.NaN);
               } else {
                  int encoded = red << 16 | green << 8 | blue;
                  double elevation = encoded < 1 << 23 ? encoded * GSI_UNIT_METERS : (encoded - (1 << 24)) * GSI_UNIT_METERS;
                  raster.set(x, y, (float)elevation);
               }
            }
         }

         return raster;
      }
   }

   private static JapanGsiElevationSource.TileCoordinate tileCoordinateForLatLon(double lat, double lon, int zoom) {
      if (!(lat >= MIN_LAT) || !(lat <= MAX_LAT) || !(lon >= MIN_LON) || !(lon <= MAX_LON)) {
         return null;
      } else {
         double clampedLat = Mth.clamp(lat, MIN_LAT, MAX_LAT);
         double clampedLon = Mth.clamp(lon, MIN_LON, MAX_LON);
         double latRad = Math.toRadians(clampedLat);
         double n = Math.pow(2.0, zoom);
         double tileX = (clampedLon + 180.0) / 360.0 * n;
         double tileY = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n;
         if (!(tileX >= 0.0) || !(tileY >= 0.0) || !(tileX < n) || !(tileY < n)) {
            return null;
         } else {
            return new JapanGsiElevationSource.TileCoordinate(zoom, tileX, tileY, Mth.floor(tileX), Mth.floor(tileY));
         }
      }
   }

   private static int selectZoom(double resolutionMeters, int maxZoom) {
      if (!(resolutionMeters > 0.0) || !Double.isFinite(resolutionMeters)) {
         return maxZoom;
      } else {
         double zoom = Math.log(EQUATOR_CIRCUMFERENCE / (TILE_SIZE * resolutionMeters)) / Math.log(2.0);
         return Mth.clamp((int)Math.round(zoom), MIN_ZOOM, maxZoom);
      }
   }

   private static double resolveSampleResolutionMeters(double worldScale, double targetResolutionMeters) {
      return targetResolutionMeters > 0.0 && Double.isFinite(targetResolutionMeters) ? targetResolutionMeters : worldScale;
   }

   private static JapanGsiElevationSource.TileLayer[] prefetchLayers(double sampleResolutionMeters) {
      if (!(sampleResolutionMeters > 0.0) || !Double.isFinite(sampleResolutionMeters) || sampleResolutionMeters <= 1.5) {
         return ALL_LAYERS;
      } else {
         return sampleResolutionMeters <= 6.0 ? MEDIUM_RES_LAYERS : COARSE_LAYERS;
      }
   }

   private static JapanGsiElevationSource.LatLon toLatLon(double blockX, double blockZ, double worldScale) {
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      double lon = blockX / blocksPerDegree;
      double lat = EarthProjection.blockZToLat(blockZ, worldScale);
      return lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0 ? new JapanGsiElevationSource.LatLon(lat, lon) : null;
   }

   private static boolean isInCoverageBox(double lat, double lon) {
      return inBox(lat, lon, 30.0, 41.8, 129.0, 145.8)
         || inBox(lat, lon, 41.0, 46.5, 139.0, 146.5)
         || inBox(lat, lon, 24.0, 30.0, 123.0, 130.5)
         || inBox(lat, lon, 23.0, 28.5, 141.0, 145.5)
         || inBox(lat, lon, 25.2, 26.9, 130.5, 132.2);
   }

   private static boolean inBox(double lat, double lon, double minLat, double maxLat, double minLon, double maxLon) {
      return lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon;
   }

   private static int intProperty(String key, int defaultValue) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      } else {
         try {
            return Math.max(1, Integer.parseInt(value));
         } catch (NumberFormatException error) {
            return defaultValue;
         }
      }
   }

   @Override
   public TellusCacheDomain cacheDomain() {
      return TellusCacheDomain.JAPANGSI;
   }

   @Override
   public void clearCache() {
      this.tileCache.invalidateAll();
      this.tileCache.cleanUp();
   }

   private record LatLon(double lat, double lon) {
   }

   private record TileCoordinate(int zoom, double tileCoordX, double tileCoordY, int tileX, int tileY) {
      private double globalPixelX() {
         return this.tileCoordX * TILE_SIZE;
      }

      private double globalPixelY() {
         return this.tileCoordY * TILE_SIZE;
      }
   }

   private record TileRecord(FloatRaster raster, boolean missing) {
   }

   private static final class MissingTileException extends IOException {
      private static final long serialVersionUID = 1L;
   }

   private record TileKey(JapanGsiElevationSource.TileLayer layer, int zoom, int x, int y) {
      private TileKey {
         Objects.requireNonNull(layer, "layer");
      }

      private String url() {
         return BASE_ENDPOINT + "/" + this.layer.pathSegment() + "/" + this.zoom + "/" + this.x + "/" + this.y + ".png";
      }

      private Path cachePath(Path cacheRoot) {
         return cacheRoot.resolve(this.layer.pathSegment()).resolve(Integer.toString(this.zoom)).resolve(Integer.toString(this.x)).resolve(this.y + ".png");
      }

      private Path missingMarkerPath(Path cacheRoot) {
         return cacheRoot.resolve(this.layer.pathSegment()).resolve(Integer.toString(this.zoom)).resolve(Integer.toString(this.x)).resolve(this.y + ".missing");
      }
   }

   private static enum TileLayer {
      DEM1A("dem1a_png", 17, 1.0),
      DEM5A("dem5a_png", 15, 5.0),
      DEM5B("dem5b_png", 15, 5.0),
      DEM5C("dem5c_png", 15, 5.0),
      DEM10B("dem_png", 14, 10.0);

      private final String pathSegment;
      private final int maxZoom;
      private final double nominalResolutionMeters;

      private TileLayer(String pathSegment, int maxZoom, double nominalResolutionMeters) {
         this.pathSegment = pathSegment;
         this.maxZoom = maxZoom;
         this.nominalResolutionMeters = nominalResolutionMeters;
      }

      private String pathSegment() {
         return this.pathSegment;
      }

      private int maxZoom() {
         return this.maxZoom;
      }

      private double nominalResolutionMeters() {
         return this.nominalResolutionMeters;
      }
   }

   public record Sample(double elevation, TellusElevationSource.DemUsage usage, double resolutionMeters) {
      private static JapanGsiElevationSource.Sample none() {
         return new JapanGsiElevationSource.Sample(Double.NaN, null, Double.NaN);
      }

      public boolean usable() {
         return this.usage != null && Double.isFinite(this.elevation);
      }
   }
}
