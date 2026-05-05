package com.yucareux.tellus.client.widget.map;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.util.TellusDiagnostics;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

@Environment(EnvType.CLIENT)
public class SlippyMapTileCache {
   private static final int CACHE_SIZE = 1024;
   private static final String TILE_ENDPOINTS_PROPERTY = "tellus.map.tile.endpoints";
   private static final String DEFAULT_TILE_ENDPOINTS = String.join(
      ",",
      "https://tile.openstreetmap.org/%d/%d/%d.png",
      "https://a.tile.openstreetmap.org/%d/%d/%d.png",
      "https://b.tile.openstreetmap.org/%d/%d/%d.png",
      "https://c.tile.openstreetmap.org/%d/%d/%d.png",
      "https://tile.openstreetmap.de/%d/%d/%d.png"
   );
   private static final String[] TILE_ENDPOINTS = parseTileEndpoints(System.getProperty(TILE_ENDPOINTS_PROPERTY, DEFAULT_TILE_ENDPOINTS));
   private final ExecutorService loadingService = Executors.newFixedThreadPool(
      4, new ThreadFactoryBuilder().setDaemon(true).setNameFormat("tellus-map-load-%d").build()
   );
   private final Queue<InputStream> loadingStreams = new LinkedBlockingQueue<>();
   private final Path cacheRoot = Minecraft.getInstance().gameDirectory.toPath().resolve("tellus/cache/map");
   private volatile boolean shuttingDown;
   private final LoadingCache<SlippyMapTilePos, SlippyMapTile> tileCache = CacheBuilder.newBuilder().maximumSize(CACHE_SIZE).removalListener(notification -> {
      SlippyMapTile tile = (SlippyMapTile)notification.getValue();
      if (tile != null) {
         tile.delete();
      }
   }).build(new CacheLoader<SlippyMapTilePos, SlippyMapTile>() {
      public SlippyMapTile load(SlippyMapTilePos key) {
         SlippyMapTile tile = new SlippyMapTile(key);
         try {
            SlippyMapTileCache.this.loadingService.submit(() -> {
               NativeImage image = SlippyMapTileCache.this.downloadImage(key);
               if (image == null) {
                  return;
               }

               if (SlippyMapTileCache.this.shuttingDown) {
                  image.close();
               } else {
                  tile.supplyImage(image);
               }
            });
         } catch (RejectedExecutionException ignored) {
            tile.supplyImage(SlippyMapTileCache.this.createErrorImage());
         }

         return tile;
      }
   });

   public SlippyMapTile getTile(SlippyMapTilePos pos) {
      try {
         return (SlippyMapTile)this.tileCache.get(pos);
      } catch (Exception var4) {
         SlippyMapTile tile = new SlippyMapTile(pos);
         tile.supplyImage(this.createErrorImage());
         return tile;
      }
   }

   public void shutdown() {
      this.shuttingDown = true;
      this.loadingService.shutdownNow();
      this.tileCache.invalidateAll();
      this.tileCache.cleanUp();

      while (!this.loadingStreams.isEmpty()) {
         try {
            InputStream poll = this.loadingStreams.poll();
            if (poll != null) {
               poll.close();
            }
         } catch (IOException var3) {
            Tellus.LOGGER.warn("Failed to close loading map stream", var3);
         }
      }
   }

   private NativeImage downloadImage(SlippyMapTilePos pos) {
      try {
         byte[] data = this.readTileData(pos);
         return data == null ? null : NativeImage.read(new ByteArrayInputStream(data));
      } catch (IOException var4) {
         if (this.isCancelledLoad(var4)) {
            return null;
         }

         Tellus.LOGGER.error("Failed to load map tile {}", pos, var4);
         return this.createErrorImage();
      }
   }

   private byte[] readTileData(SlippyMapTilePos pos) throws IOException {
      Path cachePath = this.cacheRoot.resolve(pos.getCacheName());
      if (Files.exists(cachePath)) {
         return Files.readAllBytes(cachePath);
      } else {
         byte[] data = this.fetchTileData(pos);
         if (!this.shuttingDown && !Thread.currentThread().isInterrupted()) {
            this.cacheData(cachePath, data);
         }
         return data;
      }
   }

   private byte[] fetchTileData(SlippyMapTilePos pos) throws IOException {
      IOException lastError = null;
      for (String endpoint : TILE_ENDPOINTS) {
         String requestUrl;
         try {
            requestUrl = String.format(Locale.ROOT, endpoint, pos.getZoom(), pos.getX(), pos.getY());
         } catch (RuntimeException error) {
            lastError = new IOException("Invalid map tile endpoint template: " + endpoint, error);
            TellusDiagnostics.traffic("Slippy map tile endpoint invalid endpoint=%s error=%s", endpoint, lastError.getMessage());
            continue;
         }

         HttpURLConnection connection = (HttpURLConnection)URI.create(requestUrl).toURL().openConnection();
         try {
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("User-Agent", "Tellus/2.0.0 (Minecraft Mod)");
            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
               throw new IOException("HTTP " + responseCode);
            }

            InputStream stream = Objects.requireNonNull(connection.getInputStream(), "tileStream");
            this.loadingStreams.add(stream);

            try (InputStream input = new BufferedInputStream(stream)) {
               byte[] data = input.readAllBytes();
               TellusDiagnostics.traffic("Slippy map tile ok tile=%s endpoint=%s bytes=%d", pos, requestUrl, data.length);
               return data;
            } finally {
               this.loadingStreams.remove(stream);
            }
         } catch (IOException error) {
            lastError = error;
            TellusDiagnostics.traffic("Slippy map tile failed tile=%s endpoint=%s error=%s", pos, requestUrl, error.getMessage());
         } finally {
            connection.disconnect();
         }
      }

      throw new IOException("all map tile endpoints failed for " + pos, lastError);
   }

   private static String[] parseTileEndpoints(String config) {
      String[] parts = Objects.requireNonNull(config, "tileEndpoints").split(",");
      List<String> parsed = new ArrayList<>(parts.length);
      for (String part : parts) {
         String trimmed = part == null ? "" : part.trim();
         if (!trimmed.isEmpty()) {
            parsed.add(trimmed);
         }
      }
      return parsed.isEmpty() ? new String[]{"https://tile.openstreetmap.org/%d/%d/%d.png"} : parsed.toArray(String[]::new);
   }

   private boolean isCancelledLoad(IOException error) {
      return this.shuttingDown
         || Thread.currentThread().isInterrupted()
         || error instanceof InterruptedIOException
         || error instanceof ClosedByInterruptException;
   }

   private void cacheData(Path cachePath, byte[] data) {
      try {
         Files.createDirectories(Objects.requireNonNull(cachePath.getParent(), "cachePathParent"));
      } catch (IOException var7) {
         Tellus.LOGGER.error("Failed to create cache root", var7);
      }

      try (OutputStream output = Files.newOutputStream(cachePath)) {
         output.write(data);
      } catch (IOException var9) {
         Tellus.LOGGER.error("Failed to cache map tile", var9);
      }
   }

   private NativeImage createErrorImage() {
      NativeImage result = new NativeImage(256, 256, false);

      for (int x = 0; x < 256; x++) {
         for (int y = 0; y < 256; y++) {
            result.setPixelABGR(x, y, -16776961);
         }
      }

      return result;
   }
}
