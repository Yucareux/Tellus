package com.yucareux.tellus.integration.voxy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yucareux.tellus.Tellus;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

final class VoxyBridge {
   private static final String VOXY_MOD_ID = "voxy";
   private static final String VOXY_CONFIG_FILE = "voxy-config.json";
   private static final int DEFAULT_SECTION_RENDER_DISTANCE = 16;
   private static final int VOXY_CHUNKS_PER_SECTION = 32;
   private static final int MIN_SECTION_RENDER_DISTANCE = 2;
   private static final int MAX_SECTION_RENDER_DISTANCE = 64;
   private static final long CONFIG_REFRESH_MS = 2000L;
   private static final Object CONFIG_LOCK = new Object();
   private static final AtomicBoolean LOGGED_CONFIG_ERROR = new AtomicBoolean(false);
   private static volatile int cachedSectionRenderDistance = 16;
   private static volatile long cachedConfigModifiedMs = Long.MIN_VALUE;
   private static volatile long nextConfigRefreshMs = 0L;
   private static final Object INGEST_LOCK = new Object();
   private static final AtomicBoolean LOGGED_INGEST_ERROR = new AtomicBoolean(false);

   private static volatile Method ingestMethod;
   private static volatile boolean ingestMethodResolved;

   private VoxyBridge() {
   }

   static boolean isVoxyLoaded() {
      return ModList.get().isLoaded(VOXY_MOD_ID);
   }

   static int configuredChunkRadius() {
      int sectionRenderDistance = configuredSectionRenderDistance();
      return sectionRenderDistance * VOXY_CHUNKS_PER_SECTION;
   }

   private static int configuredSectionRenderDistance() {
      if (!isVoxyLoaded()) {
         return 0;
      } else {
         long now = System.currentTimeMillis();
         if (now < nextConfigRefreshMs) {
            return cachedSectionRenderDistance;
         } else {
            synchronized (CONFIG_LOCK) {
               if (now < nextConfigRefreshMs) {
                  return cachedSectionRenderDistance;
               } else {
                  refreshConfig(now);
                  return cachedSectionRenderDistance;
               }
            }
         }
      }
   }

   private static void refreshConfig(long now) {
      nextConfigRefreshMs = now + CONFIG_REFRESH_MS;
      Path path = configPath();
      if (!Files.exists(path)) {
         cachedSectionRenderDistance = DEFAULT_SECTION_RENDER_DISTANCE;
         cachedConfigModifiedMs = Long.MIN_VALUE;
      } else {
         try {
            long modifiedMs = Files.getLastModifiedTime(path).toMillis();
            if (cachedConfigModifiedMs == modifiedMs) {
               return;
            }

            cachedConfigModifiedMs = modifiedMs;
            cachedSectionRenderDistance = readSectionRenderDistance(path);
            LOGGED_CONFIG_ERROR.set(false);
         } catch (IOException var5) {
            cachedSectionRenderDistance = DEFAULT_SECTION_RENDER_DISTANCE;
            if (LOGGED_CONFIG_ERROR.compareAndSet(false, true)) {
               Tellus.LOGGER.warn("Failed to read Voxy config from {}", path, var5);
            }
         }
      }
   }

   private static int readSectionRenderDistance(Path path) throws IOException {
      try {
         byte var3;
         try (Reader reader = Files.newBufferedReader(path)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root != null && root.has("section_render_distance")) {
               return Mth.clamp(root.get("section_render_distance").getAsInt(), MIN_SECTION_RENDER_DISTANCE, MAX_SECTION_RENDER_DISTANCE);
            }

            var3 = DEFAULT_SECTION_RENDER_DISTANCE;
         }

         return var3;
      } catch (RuntimeException var6) {
         throw new IOException("Invalid Voxy config structure", var6);
      }
   }

   private static Path configPath() {
      return FMLPaths.CONFIGDIR.get().resolve(VOXY_CONFIG_FILE);
   }

   static void tryIngestChunkAsync(MinecraftServer server, LevelChunk chunk) {
      if (isVoxyLoaded()) {
         server.execute(() -> tryIngestChunkOnServerThread(chunk));
      }
   }

   private static void tryIngestChunkOnServerThread(LevelChunk chunk) {
      Method method = resolveIngestMethod();
      if (method != null) {
         try {
            method.invoke(null, chunk);
            LOGGED_INGEST_ERROR.set(false);
         } catch (RuntimeException | ReflectiveOperationException var3) {
            if (LOGGED_INGEST_ERROR.compareAndSet(false, true)) {
               Tellus.LOGGER.warn("Failed to invoke Voxy chunk ingest bridge", var3);
            }
         }
      }
   }

   private static Method resolveIngestMethod() {
      if (ingestMethodResolved) {
         return ingestMethod;
      } else {
         synchronized (INGEST_LOCK) {
            if (ingestMethodResolved) {
               return ingestMethod;
            } else {
               try {
                  Class<?> serviceClass = Class.forName("me.cortex.voxy.common.world.service.VoxelIngestService");
                  ingestMethod = serviceClass.getMethod("tryAutoIngestChunk", LevelChunk.class);
               } catch (ReflectiveOperationException var3) {
                  Tellus.LOGGER.debug("Voxy ingest bridge unavailable", var3);
                  ingestMethod = null;
               }

               ingestMethodResolved = true;
               return ingestMethod;
            }
         }
      }
   }
}
