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
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.zip.InflaterInputStream;
import net.neoforged.fml.loading.FMLPaths;
import net.minecraft.util.Mth;

public final class AhnElevationSource implements TellusCacheHandle {
   private static final int HTTP_CONNECT_TIMEOUT = 8000;
   private static final int HTTP_READ_TIMEOUT = 8000;
   private static final String HTTP_USER_AGENT = "Tellus/1.0 (Minecraft Mod)";
   private static final int MAX_FILE_CACHE = intProperty("tellus.ahn.cacheFiles", 8);
   private static final int MAX_TILE_CACHE = intProperty("tellus.ahn.cacheTiles", 16);
   private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("tellus.ahn.enabled", "true"));
   private static final double DEFAULT_NO_DATA = -9999.0;
   private static final double REF_LAT = 52.15517440;
   private static final double REF_LON = 5.38720621;
   private final Path cacheRoot;
   private final AhnCoverageIndex coverageIndex = AhnCoverageIndex.create();
   private final LoadingCache<AhnCoverageIndex.TileReference, AhnElevationSource.TileFile> fileCache;

   public AhnElevationSource() {
      this.cacheRoot = FMLPaths.GAMEDIR.get().resolve("tellus/cache/elevation-ahn/dtm_05m");
      this.fileCache = CacheBuilder.newBuilder().maximumSize(MAX_FILE_CACHE).build(new CacheLoader<AhnCoverageIndex.TileReference, AhnElevationSource.TileFile>() {
         public AhnElevationSource.TileFile load(AhnCoverageIndex.TileReference key) throws Exception {
            return AhnElevationSource.this.loadTile(key);
         }
      });
      TellusCacheRegistry.register(this);
   }

   public boolean isLikelyInCoverage(double lat, double lon) {
      if (!this.canUse()) {
         return false;
      } else {
         AhnElevationSource.Projection projection = project(lat, lon);
         return projection != null && this.coverageIndex.find(projection.x(), projection.y()) != null;
      }
   }

   public AhnElevationSource.Sample sample(double blockX, double blockZ, double worldScale) {
      if (!this.canUse() || worldScale <= 0.0) {
         return AhnElevationSource.Sample.none();
      } else {
         AhnElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
         return latLon == null ? AhnElevationSource.Sample.none() : this.sample(latLon.lat(), latLon.lon());
      }
   }

   public AhnElevationSource.Sample sampleLocalOnly(double blockX, double blockZ, double worldScale) {
      if (!this.canUse() || worldScale <= 0.0) {
         return AhnElevationSource.Sample.none();
      } else {
         AhnElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
         return latLon == null ? AhnElevationSource.Sample.none() : this.sampleLocalOnly(latLon.lat(), latLon.lon());
      }
   }

   public AhnElevationSource.Sample sample(double lat, double lon) {
      if (!this.canUse()) {
         return AhnElevationSource.Sample.none();
      } else {
         AhnElevationSource.Projection projection = project(lat, lon);
         if (projection == null) {
            return AhnElevationSource.Sample.none();
         } else {
            AhnCoverageIndex.TileReference tileRef = this.coverageIndex.find(projection.x(), projection.y());
            if (tileRef == null) {
               return AhnElevationSource.Sample.none();
            } else {
               AhnElevationSource.TileFile tile = this.getTile(tileRef);
               if (tile == null) {
                  return AhnElevationSource.Sample.none();
               } else {
                  double value = tile.sampleProjected(projection.x(), projection.y());
                  return Double.isFinite(value)
                     ? new AhnElevationSource.Sample(value, TellusElevationSource.DemUsage.AHN, tile.effectiveResolutionMeters())
                     : AhnElevationSource.Sample.none();
               }
            }
         }
      }
   }

   private AhnElevationSource.Sample sampleLocalOnly(double lat, double lon) {
      if (!this.canUse()) {
         return AhnElevationSource.Sample.none();
      } else {
         AhnElevationSource.Projection projection = project(lat, lon);
         if (projection == null) {
            return AhnElevationSource.Sample.none();
         } else {
            AhnCoverageIndex.TileReference tileRef = this.coverageIndex.find(projection.x(), projection.y());
            if (tileRef == null) {
               return AhnElevationSource.Sample.none();
            } else {
               AhnElevationSource.TileFile tile = this.getTileLocalOnly(tileRef);
               if (tile == null) {
                  return AhnElevationSource.Sample.none();
               } else {
                  double value = tile.sampleProjected(projection.x(), projection.y());
                  return Double.isFinite(value)
                     ? new AhnElevationSource.Sample(value, TellusElevationSource.DemUsage.AHN, tile.effectiveResolutionMeters())
                     : AhnElevationSource.Sample.none();
               }
            }
         }
      }
   }

   public void prefetchTiles(double blockX, double blockZ, double worldScale, int radius) {
      if (!(worldScale <= 0.0) && radius > 0 && this.canUse()) {
         LinkedHashSet<AhnCoverageIndex.TileReference> refs = new LinkedHashSet<>();
         double blockRadius = Math.max(1, radius) * 256.0;

         for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
               AhnElevationSource.LatLon latLon = toLatLon(blockX + dx * blockRadius, blockZ + dz * blockRadius, worldScale);
               if (latLon != null) {
                  AhnElevationSource.Projection projection = project(latLon.lat(), latLon.lon());
                  if (projection != null) {
                     AhnCoverageIndex.TileReference ref = this.coverageIndex.find(projection.x(), projection.y());
                     if (ref != null) {
                        refs.add(ref);
                     }
                  }
               }
            }
         }

         for (AhnCoverageIndex.TileReference ref : refs) {
            this.prefetchTile(ref);
         }
      }
   }

   private boolean canUse() {
      return ENABLED && this.coverageIndex.available();
   }

   private void prefetchTile(AhnCoverageIndex.TileReference tileRef) {
      if (this.fileCache.getIfPresent(tileRef) == null) {
         try {
            this.fileCache.get(tileRef);
         } catch (ExecutionException error) {
            Tellus.LOGGER.debug("Failed to prefetch AHN tile {}", tileRef.id(), error);
         }
      }
   }

   private AhnElevationSource.TileFile getTile(AhnCoverageIndex.TileReference tileRef) {
      try {
         return this.fileCache.get(tileRef);
      } catch (ExecutionException error) {
         Tellus.LOGGER.debug("Failed to load AHN tile {}", tileRef.id(), error);
         return null;
      }
   }

   private AhnElevationSource.TileFile getTileLocalOnly(AhnCoverageIndex.TileReference tileRef) {
      AhnElevationSource.TileFile cached = this.fileCache.getIfPresent(tileRef);
      if (cached != null) {
         return cached;
      } else {
         Path cacheDir = this.cacheRoot.resolve(tileRef.cacheDirectory());
         if (!Files.isDirectory(cacheDir)) {
            return null;
         } else {
            try {
               AhnElevationSource.TileFile opened = AhnElevationSource.TileFile.openLocalOnly(tileRef, cacheDir);
               AhnElevationSource.TileFile raced = this.fileCache.asMap().putIfAbsent(tileRef, opened);
               return raced != null ? raced : opened;
            } catch (IOException error) {
               Tellus.LOGGER.debug("Failed to load cached AHN tile {}", tileRef.id(), error);
               return null;
            }
         }
      }
   }

   private AhnElevationSource.TileFile loadTile(AhnCoverageIndex.TileReference tileRef) throws Exception {
      return AhnElevationSource.TileFile.open(tileRef, this.cacheRoot.resolve(tileRef.cacheDirectory()));
   }

   private static AhnElevationSource.LatLon toLatLon(double blockX, double blockZ, double worldScale) {
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      double lon = blockX / blocksPerDegree;
      double lat = EarthProjection.blockZToLat(blockZ, worldScale);
      return lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0 ? new AhnElevationSource.LatLon(lat, lon) : null;
   }

   private static AhnElevationSource.Projection project(double latDeg, double lonDeg) {
      if (!(latDeg >= 50.0) || !(latDeg <= 55.0) || !(lonDeg >= 2.0) || !(lonDeg <= 8.0)) {
         return null;
      } else {
         double dLat = 0.36 * (latDeg - REF_LAT);
         double dLon = 0.36 * (lonDeg - REF_LON);
         double x = 155000.0
            - 0.705 * dLat
            + 190094.945 * dLon
            - 11832.228 * dLat * dLon
            - 114.221 * dLat * dLat * dLon
            - 32.391 * dLon * dLon * dLon
            - 2.340 * dLat * dLat * dLon * dLon
            - 0.608 * dLat * dLon * dLon * dLon
            - 0.008 * dLat * dLat * dLon * dLon * dLon;
         double y = 463000.0
            + 309056.544 * dLat
            + 0.433 * dLon
            + 3638.893 * dLon * dLon
            + 73.077 * dLat * dLat
            - 157.984 * dLat * dLon * dLon
            + 59.788 * dLat * dLat * dLat
            - 6.439 * dLat * dLat * dLon
            - 0.032 * dLat * dLon * dLon
            + 0.092 * dLon * dLon * dLon * dLon
            - 0.054 * dLat * dLat * dLat * dLat;
         return new AhnElevationSource.Projection(x, y);
      }
   }

   private static HttpURLConnection openConnection(URI uri, String rangeHeader) throws IOException {
      HttpURLConnection connection = (HttpURLConnection)uri.toURL().openConnection();
      connection.setConnectTimeout(HTTP_CONNECT_TIMEOUT);
      connection.setReadTimeout(HTTP_READ_TIMEOUT);
      connection.setRequestProperty("User-Agent", HTTP_USER_AGENT);
      if (rangeHeader != null) {
         connection.setRequestProperty("Range", rangeHeader);
      }

      return connection;
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
      return TellusCacheDomain.AHN;
   }

   @Override
   public void clearCache() {
      this.fileCache.invalidateAll();
      this.fileCache.cleanUp();
   }

   private record LatLon(double lat, double lon) {
   }

   private record Projection(double x, double y) {
   }

   public record Sample(double elevation, TellusElevationSource.DemUsage usage, double resolutionMeters) {
      private static AhnElevationSource.Sample none() {
         return new AhnElevationSource.Sample(Double.NaN, null, Double.NaN);
      }

      public boolean usable() {
         return this.usage != null && Double.isFinite(this.elevation);
      }
   }

   private static final class CachedRangeReader {
      private final AhnCoverageIndex.TileReference tileRef;
      private final Path cacheDir;
      private final boolean localOnly;

      private CachedRangeReader(AhnCoverageIndex.TileReference tileRef, Path cacheDir) {
         this(tileRef, cacheDir, false);
      }

      private CachedRangeReader(AhnCoverageIndex.TileReference tileRef, Path cacheDir, boolean localOnly) {
         this.tileRef = Objects.requireNonNull(tileRef, "tileRef");
         this.cacheDir = Objects.requireNonNull(cacheDir, "cacheDir");
         this.localOnly = localOnly;
      }

      private byte[] read(long offset, int length) throws IOException {
         if (length <= 0) {
            return new byte[0];
         } else {
            Path cachePath = this.cacheDir.resolve(offset + "-" + length + ".bin");
            byte[] cached = this.readCachedRange(cachePath, length);
            if (cached != null) {
               return cached;
            } else {
               if (this.localOnly) {
                  throw new EOFException("AHN range not cached for " + this.tileRef.id());
               }

               byte[] data = this.readRemoteRange(offset, length);
               this.writeCachedRange(cachePath, data);
               return data;
            }
         }
      }

      private byte[] readCachedRange(Path cachePath, int expectedLength) throws IOException {
         if (!Files.exists(cachePath)) {
            return null;
         } else {
            byte[] data = Files.readAllBytes(cachePath);
            if (data.length == expectedLength) {
               return data;
            } else {
               Files.deleteIfExists(cachePath);
               return null;
            }
         }
      }

      private byte[] readRemoteRange(long offset, int length) throws IOException {
         String rangeHeader = "bytes=" + offset + "-" + (offset + length - 1L);
         HttpURLConnection connection = AhnElevationSource.openConnection(URI.create(this.tileRef.url()), rangeHeader);

         try {
            int status = connection.getResponseCode();
            if (status != 200 && status != 206) {
               throw new IOException("Unexpected AHN HTTP status " + status + " for " + this.tileRef.id());
            } else {
               DownloadProgressReporter.requestStarted((long)length);
               try (InputStream input = connection.getInputStream()) {
                  byte[] data = DownloadProgressReporter.readAllBytesWithProgress(input);
                  if (data.length != length) {
                     throw new EOFException("Unexpected AHN range length " + data.length + " for " + this.tileRef.id());
                  } else {
                     return data;
                  }
               }
            }
         } finally {
            DownloadProgressReporter.requestFinished();
            connection.disconnect();
         }
      }

      private void writeCachedRange(Path cachePath, byte[] data) {
         try {
            Files.createDirectories(cachePath.getParent());
            Path tempPath = cachePath.resolveSibling(cachePath.getFileName() + ".tmp");
            Files.write(tempPath, data);
            Files.move(tempPath, cachePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         } catch (IOException error) {
            Tellus.LOGGER.debug("Failed to cache AHN range {}", cachePath, error);
         }
      }
   }

   private static final class TileFile {
      private static final int TAG_IMAGE_WIDTH = 256;
      private static final int TAG_IMAGE_HEIGHT = 257;
      private static final int TAG_BITS_PER_SAMPLE = 258;
      private static final int TAG_COMPRESSION = 259;
      private static final int TAG_STRIP_OFFSETS = 273;
      private static final int TAG_SAMPLES_PER_PIXEL = 277;
      private static final int TAG_ROWS_PER_STRIP = 278;
      private static final int TAG_STRIP_BYTE_COUNTS = 279;
      private static final int TAG_PREDICTOR = 317;
      private static final int TAG_TILE_WIDTH = 322;
      private static final int TAG_TILE_HEIGHT = 323;
      private static final int TAG_TILE_OFFSETS = 324;
      private static final int TAG_TILE_BYTE_COUNTS = 325;
      private static final int TAG_SAMPLE_FORMAT = 339;
      private static final int TAG_MODEL_PIXEL_SCALE = 33550;
      private static final int TAG_MODEL_TIEPOINT = 33922;
      private static final int TAG_GDAL_NODATA = 42113;
      private static final int TYPE_BYTE = 1;
      private static final int TYPE_ASCII = 2;
      private static final int TYPE_SHORT = 3;
      private static final int TYPE_LONG = 4;
      private static final int TYPE_DOUBLE = 12;
      private static final int TYPE_LONG8 = 16;
      private static final int COMPRESSION_NONE = 1;
      private static final int COMPRESSION_LZW = 5;
      private static final int COMPRESSION_DEFLATE = 8;
      private final String tileId;
      private final AhnElevationSource.CachedRangeReader reader;
      private final ByteOrder order;
      private final int width;
      private final int height;
      private final int tileWidth;
      private final int tileHeight;
      private final int tilesPerRow;
      private final int compression;
      private final int predictor;
      private final int bytesPerSample;
      private final int samplesPerPixel;
      private final long[] tileOffsets;
      private final int[] tileByteCounts;
      private final double originX;
      private final double originY;
      private final double pixelSizeX;
      private final double pixelSizeY;
      private final float noData;
      private final Map<Integer, float[]> tileCache;

      private TileFile(
         String tileId,
         AhnElevationSource.CachedRangeReader reader,
         ByteOrder order,
         int width,
         int height,
         int tileWidth,
         int tileHeight,
         int compression,
         int predictor,
         int bytesPerSample,
         int samplesPerPixel,
         long[] tileOffsets,
         int[] tileByteCounts,
         double originX,
         double originY,
         double pixelSizeX,
         double pixelSizeY,
         float noData
      ) {
         this.tileId = tileId;
         this.reader = reader;
         this.order = order;
         this.width = width;
         this.height = height;
         this.tileWidth = tileWidth;
         this.tileHeight = tileHeight;
         this.tilesPerRow = (int)Math.ceil((double)width / tileWidth);
         this.compression = compression;
         this.predictor = predictor;
         this.bytesPerSample = bytesPerSample;
         this.samplesPerPixel = samplesPerPixel;
         this.tileOffsets = tileOffsets;
         this.tileByteCounts = tileByteCounts;
         this.originX = originX;
         this.originY = originY;
         this.pixelSizeX = pixelSizeX;
         this.pixelSizeY = pixelSizeY;
         this.noData = noData;
         this.tileCache = new LinkedHashMap<Integer, float[]>(MAX_TILE_CACHE, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Entry<Integer, float[]> eldest) {
               return this.size() > MAX_TILE_CACHE;
            }
         };
      }

      private double sampleProjected(double x, double y) {
         double globalX = (x - this.originX) / this.pixelSizeX;
         double globalY = (this.originY - y) / this.pixelSizeY;
         if (globalX < -1.0 || globalY < -1.0 || globalX > this.width || globalY > this.height) {
            return Double.NaN;
         } else {
            int maxPixelX = this.width - 1;
            int maxPixelY = this.height - 1;
            double clampedX = Mth.clamp(globalX, 0.0, maxPixelX);
            double clampedY = Mth.clamp(globalY, 0.0, maxPixelY);
            int x0 = Mth.floor(clampedX);
            int y0 = Mth.floor(clampedY);
            int x1 = Math.min(x0 + 1, maxPixelX);
            int y1 = Math.min(y0 + 1, maxPixelY);
            double dx = clampedX - x0;
            double dy = clampedY - y0;
            float v00 = this.sampleValue(x0, y0);
            float v10 = this.sampleValue(x1, y0);
            float v01 = this.sampleValue(x0, y1);
            float v11 = this.sampleValue(x1, y1);
            return blendFiniteSamples(v00, v10, v01, v11, dx, dy);
         }
      }

      private double effectiveResolutionMeters() {
         return (Math.abs(this.pixelSizeX) + Math.abs(this.pixelSizeY)) * 0.5;
      }

      private float sampleValue(int pixelX, int pixelY) {
         int clampedX = Mth.clamp(pixelX, 0, this.width - 1);
         int clampedY = Mth.clamp(pixelY, 0, this.height - 1);
         int tileX = clampedX / this.tileWidth;
         int tileY = clampedY / this.tileHeight;
         int tileIndex = tileY * this.tilesPerRow + tileX;

         try {
            float[] tile = this.getTile(tileIndex);
            int localX = clampedX - tileX * this.tileWidth;
            int localY = clampedY - tileY * this.tileHeight;
            return tile[localX + localY * this.tileWidth];
         } catch (IOException error) {
            Tellus.LOGGER.debug("Failed to read AHN tile {} block from {}", tileIndex, this.tileId, error);
            return Float.NaN;
         }
      }

      private float[] getTile(int tileIndex) throws IOException {
         synchronized(this.tileCache) {
            float[] cached = this.tileCache.get(tileIndex);
            if (cached != null) {
               return cached;
            }
         }

         float[] tile = this.readTile(tileIndex);
         synchronized(this.tileCache) {
            this.tileCache.put(tileIndex, tile);
            return tile;
         }
      }

      private float[] readTile(int tileIndex) throws IOException {
         long offset = this.tileOffsets[tileIndex];
         int length = this.tileByteCounts[tileIndex];
         byte[] compressed = this.reader.read(offset, length);
         int expectedSize = this.tileWidth * this.tileHeight * this.bytesPerSample * this.samplesPerPixel;
         if (this.samplesPerPixel != 1) {
            throw new IOException("Unsupported AHN TIFF samples per pixel " + this.samplesPerPixel);
         } else {
            byte[] raw = switch (this.compression) {
               case COMPRESSION_NONE -> compressed;
               case COMPRESSION_LZW -> decompressLzw(compressed, expectedSize);
               case COMPRESSION_DEFLATE -> inflate(compressed, expectedSize);
               default -> throw new IOException("Unsupported AHN TIFF compression " + this.compression);
            };
            if (raw.length != expectedSize) {
               throw new IOException("Unexpected AHN tile length " + raw.length + " for " + this.tileId);
            } else {
               if (this.predictor == 3) {
                  applyFloatingPointPredictor(raw, this.tileWidth, this.tileHeight, this.bytesPerSample, this.samplesPerPixel, this.order);
               } else if (this.predictor != 1) {
                  throw new IOException("Unsupported AHN TIFF predictor " + this.predictor);
               }

               float[] values = new float[this.tileWidth * this.tileHeight];
               ByteBuffer buffer = ByteBuffer.wrap(raw).order(this.order);

               for (int i = 0; i < values.length; i++) {
                  float value = buffer.getFloat();
                  values[i] = value <= this.noData + 1.0F ? Float.NaN : value;
               }

               return values;
            }
         }
      }

      private static AhnElevationSource.TileFile open(AhnCoverageIndex.TileReference tileRef, Path cacheDir) throws IOException {
         return open(tileRef, cacheDir, false);
      }

      private static AhnElevationSource.TileFile openLocalOnly(AhnCoverageIndex.TileReference tileRef, Path cacheDir) throws IOException {
         return open(tileRef, cacheDir, true);
      }

      private static AhnElevationSource.TileFile open(AhnCoverageIndex.TileReference tileRef, Path cacheDir, boolean localOnly) throws IOException {
         AhnElevationSource.CachedRangeReader reader = new AhnElevationSource.CachedRangeReader(tileRef, cacheDir, localOnly);
         byte[] header = reader.read(0L, 32);
         ByteOrder order = switch (header[0]) {
            case 73 -> ByteOrder.LITTLE_ENDIAN;
            case 77 -> ByteOrder.BIG_ENDIAN;
            default -> throw new IOException("Invalid AHN TIFF byte order for " + tileRef.id());
         };
         ByteBuffer headerBuf = ByteBuffer.wrap(header).order(order);
         headerBuf.getShort();
         short magic = headerBuf.getShort();
         if (magic == 42) {
            return openStandardTiff(tileRef, reader, order, headerBuf);
         } else if (magic == 43) {
            return openBigTiff(tileRef, reader, order, headerBuf);
         } else {
            throw new IOException("Unsupported AHN TIFF magic " + magic + " for " + tileRef.id());
         }
      }

      private static AhnElevationSource.TileFile openStandardTiff(
         AhnCoverageIndex.TileReference tileRef, AhnElevationSource.CachedRangeReader reader, ByteOrder order, ByteBuffer headerBuf
      ) throws IOException {
         long ifdOffset = Integer.toUnsignedLong(headerBuf.getInt());
         int entryCount = Short.toUnsignedInt(ByteBuffer.wrap(reader.read(ifdOffset, 2)).order(order).getShort());
         byte[] entryBytes = reader.read(ifdOffset + 2L, entryCount * 12);
         ByteBuffer entries = ByteBuffer.wrap(entryBytes).order(order);
         return buildTileFile(tileRef, reader, order, false, entryCount, entries);
      }

      private static AhnElevationSource.TileFile openBigTiff(
         AhnCoverageIndex.TileReference tileRef, AhnElevationSource.CachedRangeReader reader, ByteOrder order, ByteBuffer headerBuf
      ) throws IOException {
         short offsetSize = headerBuf.getShort();
         headerBuf.getShort();
         if (offsetSize != 8) {
            throw new IOException("Unsupported AHN BigTIFF offset size " + offsetSize + " for " + tileRef.id());
         } else {
            long ifdOffset = headerBuf.getLong();
            long entryCount = ByteBuffer.wrap(reader.read(ifdOffset, 8)).order(order).getLong();
            if (entryCount < 0L || entryCount > 1000000L) {
               throw new IOException("Invalid AHN BigTIFF entry count " + entryCount + " for " + tileRef.id());
            } else {
               byte[] entryBytes = reader.read(ifdOffset + 8L, Math.toIntExact(entryCount * 20L));
               ByteBuffer entries = ByteBuffer.wrap(entryBytes).order(order);
               return buildTileFile(tileRef, reader, order, true, entryCount, entries);
            }
         }
      }

      private static AhnElevationSource.TileFile buildTileFile(
         AhnCoverageIndex.TileReference tileRef,
         AhnElevationSource.CachedRangeReader reader,
         ByteOrder order,
         boolean bigTiff,
         long entryCount,
         ByteBuffer entries
      ) throws IOException {
         int inlineSize = bigTiff ? 8 : 4;
         int width = -1;
         int height = -1;
         int tileWidth = -1;
         int tileHeight = -1;
         int compression = -1;
         int predictor = 1;
         int bitsPerSample = -1;
         int sampleFormat = -1;
         int samplesPerPixel = 1;
         long[] tileOffsets = null;
         int[] tileByteCounts = null;
         int rowsPerStrip = -1;
         long[] stripOffsets = null;
         int[] stripByteCounts = null;
         double[] pixelScale = null;
         double[] tiePoints = null;
         float noData = (float)DEFAULT_NO_DATA;

         for (int i = 0; i < entryCount; i++) {
            int tag = Short.toUnsignedInt(entries.getShort());
            int type = Short.toUnsignedInt(entries.getShort());
            long count = bigTiff ? entries.getLong() : Integer.toUnsignedLong(entries.getInt());
            byte[] inlineBytes = new byte[inlineSize];
            if (bigTiff) {
               entries.get(inlineBytes);
            } else {
               entries.get(inlineBytes, 0, 4);
            }

            long valueOrOffset = bigTiff
               ? ByteBuffer.wrap(inlineBytes).order(order).getLong()
               : Integer.toUnsignedLong(ByteBuffer.wrap(inlineBytes).order(order).getInt());
            switch (tag) {
               case TAG_IMAGE_WIDTH -> width = readIntValue(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_IMAGE_HEIGHT -> height = readIntValue(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_BITS_PER_SAMPLE -> bitsPerSample = readIntValue(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_COMPRESSION -> compression = readIntValue(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_STRIP_OFFSETS -> stripOffsets = readLongArray(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_SAMPLES_PER_PIXEL -> samplesPerPixel = readIntValue(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_ROWS_PER_STRIP -> rowsPerStrip = readIntValue(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_STRIP_BYTE_COUNTS -> stripByteCounts = readIntArray(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_PREDICTOR -> predictor = readIntValue(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_TILE_WIDTH -> tileWidth = readIntValue(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_TILE_HEIGHT -> tileHeight = readIntValue(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_TILE_OFFSETS -> tileOffsets = readLongArray(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_TILE_BYTE_COUNTS -> tileByteCounts = readIntArray(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_SAMPLE_FORMAT -> sampleFormat = readIntValue(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_MODEL_PIXEL_SCALE -> pixelScale = readDoubleArray(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_MODEL_TIEPOINT -> tiePoints = readDoubleArray(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_GDAL_NODATA -> noData = parseNoData(readAscii(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize));
            }
         }

         if (width <= 0 || height <= 0) {
            throw new IOException("Missing AHN TIFF size tags for " + tileRef.id());
         } else {
            if ((tileOffsets == null || tileByteCounts == null) && stripOffsets != null && stripByteCounts != null && rowsPerStrip > 0) {
               tileWidth = width;
               tileHeight = rowsPerStrip;
               tileOffsets = stripOffsets;
               tileByteCounts = stripByteCounts;
            }

            if (tileWidth <= 0 || tileHeight <= 0) {
               throw new IOException("Missing AHN TIFF tile geometry for " + tileRef.id());
            } else if (tileOffsets == null || tileByteCounts == null) {
               throw new IOException("Missing AHN TIFF tile offsets for " + tileRef.id());
            } else if (bitsPerSample != 32 || sampleFormat != 3) {
               throw new IOException("Unsupported AHN TIFF sample format " + sampleFormat + " bits " + bitsPerSample);
            } else if (pixelScale != null && pixelScale.length >= 2 && tiePoints != null && tiePoints.length >= 6) {
               double pixelSizeX = pixelScale[0];
               double pixelSizeY = pixelScale[1];
               double originX = tiePoints[3] - tiePoints[0] * pixelSizeX;
               double originY = tiePoints[4] + tiePoints[1] * pixelSizeY;
               return new AhnElevationSource.TileFile(
                  tileRef.id(),
                  reader,
                  order,
                  width,
                  height,
                  tileWidth,
                  tileHeight,
                  compression,
                  predictor,
                  4,
                  samplesPerPixel,
                  tileOffsets,
                  tileByteCounts,
                  originX,
                  originY,
                  pixelSizeX,
                  pixelSizeY,
                  noData
               );
            } else {
               throw new IOException("Missing AHN TIFF georeferencing tags for " + tileRef.id());
            }
         }
      }

      private static int readIntValue(
         AhnElevationSource.CachedRangeReader reader, long valueOrOffset, byte[] inlineBytes, long count, int type, ByteOrder order, int inlineSize
      ) throws IOException {
         if (count != 1L) {
            throw new IOException("Expected single TIFF value");
         } else {
            byte[] data = readFieldData(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
            ByteBuffer buffer = ByteBuffer.wrap(data).order(order);
            return switch (type) {
               case TYPE_BYTE -> buffer.get() & 255;
               case TYPE_SHORT -> Short.toUnsignedInt(buffer.getShort());
               case TYPE_LONG -> Math.toIntExact(Integer.toUnsignedLong(buffer.getInt()));
               case TYPE_LONG8 -> Math.toIntExact(buffer.getLong());
               default -> throw new IOException("Unsupported TIFF value type " + type);
            };
         }
      }

      private static long[] readLongArray(
         AhnElevationSource.CachedRangeReader reader, long valueOrOffset, byte[] inlineBytes, long count, int type, ByteOrder order, int inlineSize
      ) throws IOException {
         if (count <= 0L) {
            return new long[0];
         } else {
            byte[] data = readFieldData(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
            ByteBuffer buffer = ByteBuffer.wrap(data).order(order);
            long[] values = new long[(int)count];

            for (int i = 0; i < count; i++) {
               values[i] = switch (type) {
                  case TYPE_BYTE -> buffer.get() & 255;
                  case TYPE_SHORT -> Short.toUnsignedInt(buffer.getShort());
                  case TYPE_LONG -> Integer.toUnsignedLong(buffer.getInt());
                  case TYPE_LONG8 -> buffer.getLong();
                  default -> throw new IOException("Unsupported TIFF array type " + type);
               };
            }

            return values;
         }
      }

      private static int[] readIntArray(
         AhnElevationSource.CachedRangeReader reader, long valueOrOffset, byte[] inlineBytes, long count, int type, ByteOrder order, int inlineSize
      ) throws IOException {
         long[] values = readLongArray(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
         int[] output = new int[values.length];

         for (int i = 0; i < values.length; i++) {
            if (values[i] > 2147483647L) {
               throw new IOException("TIFF byte count too large " + values[i]);
            }

            output[i] = (int)values[i];
         }

         return output;
      }

      private static double[] readDoubleArray(
         AhnElevationSource.CachedRangeReader reader, long valueOrOffset, byte[] inlineBytes, long count, int type, ByteOrder order, int inlineSize
      ) throws IOException {
         if (count <= 0L) {
            return new double[0];
         } else if (type != TYPE_DOUBLE) {
            throw new IOException("Unsupported TIFF double array type " + type);
         } else {
            byte[] data = readFieldData(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
            ByteBuffer buffer = ByteBuffer.wrap(data).order(order);
            double[] values = new double[(int)count];

            for (int i = 0; i < count; i++) {
               values[i] = buffer.getDouble();
            }

            return values;
         }
      }

      private static String readAscii(
         AhnElevationSource.CachedRangeReader reader, long valueOrOffset, byte[] inlineBytes, long count, int type, ByteOrder order, int inlineSize
      ) throws IOException {
         if (count <= 0L) {
            return "";
         } else if (type != TYPE_ASCII) {
            throw new IOException("Unsupported TIFF ASCII type " + type);
         } else {
            byte[] data = readFieldData(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
            int length = (int)Math.min(count, data.length);

            while (length > 0 && data[length - 1] == 0) {
               length--;
            }

            return new String(data, 0, length, StandardCharsets.US_ASCII);
         }
      }

      private static byte[] readFieldData(
         AhnElevationSource.CachedRangeReader reader, long valueOrOffset, byte[] inlineBytes, long count, int type, ByteOrder order, int inlineSize
      ) throws IOException {
         int size = typeSize(type);
         if (size <= 0) {
            throw new IOException("Unsupported TIFF field type " + type);
         } else {
            long byteSize = count * size;
            if (byteSize <= inlineSize) {
               byte[] data = new byte[(int)byteSize];
               System.arraycopy(inlineBytes, 0, data, 0, (int)byteSize);
               return data;
            } else if (byteSize > Integer.MAX_VALUE) {
               throw new IOException("TIFF field too large");
            } else {
               return reader.read(valueOrOffset, (int)byteSize);
            }
         }
      }

      private static float parseNoData(String value) {
         if (value == null || value.isBlank()) {
            return (float)DEFAULT_NO_DATA;
         } else {
            try {
               return Float.parseFloat(value.trim());
            } catch (NumberFormatException error) {
               return (float)DEFAULT_NO_DATA;
            }
         }
      }

      private static int typeSize(int type) {
         return switch (type) {
            case TYPE_BYTE, TYPE_ASCII -> 1;
            case TYPE_SHORT -> 2;
            case TYPE_LONG -> 4;
            case TYPE_DOUBLE, TYPE_LONG8 -> 8;
            default -> 0;
         };
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

         return weight <= 0.0 ? Double.NaN : sum / weight;
      }

      private static byte[] inflate(byte[] compressed, int expectedSize) throws IOException {
         byte[] output = new byte[expectedSize];

         try (InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            int offset = 0;

            while (offset < expectedSize) {
               int read = inflater.read(output, offset, expectedSize - offset);
               if (read < 0) {
                  break;
               }

               offset += read;
            }

            if (offset != expectedSize) {
               throw new IOException("Unexpected inflated data length");
            } else {
               return output;
            }
         }
      }

      private static byte[] decompressLzw(byte[] compressed, int expectedSize) throws IOException {
         byte[][] table = new byte[4096][];

         for (int i = 0; i < 256; i++) {
            table[i] = new byte[]{(byte)i};
         }

         int clearCode = 256;
         int endCode = 257;
         int codeSize = 9;
         int nextCode = 258;
         byte[] output = new byte[expectedSize];
         int outPos = 0;
         AhnElevationSource.TileFile.LzwBitReader reader = new AhnElevationSource.TileFile.LzwBitReader(compressed);
         byte[] previous = null;

         while (true) {
            int code = reader.read(codeSize);
            if (code < 0) {
               break;
            }

            if (code == clearCode) {
               for (int i = 0; i < 256; i++) {
                  table[i] = new byte[]{(byte)i};
               }

               for (int i = 256; i < table.length; i++) {
                  table[i] = null;
               }

               codeSize = 9;
               nextCode = 258;
               previous = null;
            } else {
               if (code == endCode) {
                  break;
               }

               byte[] entry;
               if (code < nextCode && table[code] != null) {
                  entry = table[code];
               } else {
                  if (code != nextCode || previous == null) {
                     throw new IOException("Invalid LZW code " + code);
                  }

                  entry = concat(previous, previous[0]);
               }

               if (outPos + entry.length > output.length) {
                  throw new IOException("Unexpected LZW output size");
               }

               System.arraycopy(entry, 0, output, outPos, entry.length);
               outPos += entry.length;
               if (previous != null && nextCode < table.length) {
                  table[nextCode++] = concat(previous, entry[0]);
                  int threshold = (1 << codeSize) - 1;
                  if (nextCode == threshold && codeSize < 12) {
                     codeSize++;
                  }
               }

               previous = entry;
               if (outPos == output.length) {
                  break;
               }
            }
         }

         if (outPos != output.length) {
            throw new IOException("Unexpected LZW output length " + outPos);
         } else {
            return output;
         }
      }

      private static void applyFloatingPointPredictor(byte[] data, int width, int height, int bytesPerSample, int samplesPerPixel, ByteOrder order)
         throws IOException {
         int rowBytes = width * bytesPerSample * samplesPerPixel;
         if (rowBytes > 0) {
            if (samplesPerPixel > 0 && rowBytes % (bytesPerSample * samplesPerPixel) == 0) {
               int wordCount = rowBytes / bytesPerSample;
               byte[] tmp = new byte[rowBytes];

               for (int row = 0; row < height; row++) {
                  int rowStart = row * rowBytes;
                  int count = rowBytes;

                  for (int offset = rowStart; count > samplesPerPixel; count -= samplesPerPixel) {
                     for (int i = 0; i < samplesPerPixel; i++) {
                        int target = offset + samplesPerPixel;
                        int value = (data[target] & 255) + (data[offset] & 255);
                        data[target] = (byte)value;
                        offset++;
                     }
                  }

                  System.arraycopy(data, rowStart, tmp, 0, rowBytes);

                  for (int word = 0; word < wordCount; word++) {
                     int base = rowStart + word * bytesPerSample;

                     for (int byteIndex = 0; byteIndex < bytesPerSample; byteIndex++) {
                        int sourceIndex = order == ByteOrder.BIG_ENDIAN ? byteIndex * wordCount + word : (bytesPerSample - byteIndex - 1) * wordCount + word;
                        data[base + byteIndex] = tmp[sourceIndex];
                     }
                  }
               }
            } else {
               throw new IOException("Invalid floating point predictor stride");
            }
         }
      }

      private static byte[] concat(byte[] prefix, byte suffix) {
         byte[] combined = new byte[prefix.length + 1];
         System.arraycopy(prefix, 0, combined, 0, prefix.length);
         combined[prefix.length] = suffix;
         return combined;
      }

      private static final class LzwBitReader {
         private final byte[] data;
         private int bitPos;

         private LzwBitReader(byte[] data) {
            this.data = data;
         }

         private int read(int width) {
            if (this.bitPos + width > this.data.length * 8) {
               return -1;
            } else {
               int value = 0;

               for (int bit = 0; bit < width; bit++) {
                  int byteIndex = (this.bitPos + bit) >> 3;
                  int bitIndex = this.bitPos + bit & 7;
                  value |= ((this.data[byteIndex] & 255) >> bitIndex & 1) << bit;
               }

               this.bitPos += width;
               return value;
            }
         }
      }
   }
}
