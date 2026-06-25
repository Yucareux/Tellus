package com.yucareux.tellus.client.widget.map;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import com.yucareux.tellus.Tellus;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import net.minecraft.client.Minecraft;

@OnlyIn(Dist.CLIENT)
public class SlippyMapTileCache {
   private static final int CACHE_SIZE = 1024;
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
         NativeImage var3;
         try (InputStream input = Objects.requireNonNull(this.getStream(pos), "tileStream")) {
            var3 = NativeImage.read(input);
         }

         return var3;
      } catch (IOException var7) {
         Tellus.LOGGER.error("Failed to load map tile {}", pos, var7);
         return this.createErrorImage();
      }
   }

   
   private InputStream getStream(SlippyMapTilePos pos) throws IOException {
      Path cachePath = this.cacheRoot.resolve(pos.getCacheName());
      if (Files.exists(cachePath)) {
         return new BufferedInputStream(Files.newInputStream(cachePath));
      } else {
         URI uri = URI.create(String.format("https://tile.openstreetmap.org/%s/%s/%s.png", pos.getZoom(), pos.getX(), pos.getY()));
         URL url = uri.toURL();
         HttpURLConnection connection = (HttpURLConnection)url.openConnection();
         try {
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("User-Agent", "Tellus/2.0.0 (Minecraft Mod)");
            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
               throw new IOException("OpenStreetMap tile request failed with HTTP " + responseCode + " for " + pos);
            }

            InputStream stream = Objects.requireNonNull(connection.getInputStream(), "tileStream");
            this.loadingStreams.add(stream);

            try (InputStream input = new BufferedInputStream(stream)) {
               byte[] data = input.readAllBytes();
               this.cacheData(cachePath, data);
               return new ByteArrayInputStream(data);
            } finally {
               this.loadingStreams.remove(stream);
            }
         } finally {
            connection.disconnect();
         }
      }
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
            result.setPixelRGBA(x, y, -16776961);
         }
      }

      return result;
   }
}
