package com.yucareux.tellus.preload;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.cache.TellusCacheDomain;
import com.yucareux.tellus.cache.TellusCacheFiles;
import com.yucareux.tellus.cache.TellusCacheRegistry;
import com.yucareux.tellus.platform.TellusPlatform;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public final class TerrainPreloadStorage {
   public static final String RELATIVE_CACHE_PATH = "tellus/cache/preloaded-terrain/v1";
   private static final long MAX_MANIFEST_BYTES = 1024L * 1024L;
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final TerrainPreloadStorage INSTANCE = new TerrainPreloadStorage();

   private TerrainPreloadStorage() {
   }

   public static TerrainPreloadStorage instance() {
      return INSTANCE;
   }

   public Path root() {
      return TellusPlatform.gameDir().resolve(RELATIVE_CACHE_PATH);
   }

   public Path createStagingDirectory(String jobId) throws IOException {
      requireValidIdentifier(jobId);
      Path stagingRoot = this.root().resolve(".staging");
      Files.createDirectories(stagingRoot);
      Path staging = stagingRoot.resolve(Objects.requireNonNull(jobId, "jobId"));
      deleteTree(staging);
      Files.createDirectories(staging);
      return staging;
   }

   public Path publishedDirectory(String id) {
      requireValidIdentifier(id);
      return this.root().resolve(id);
   }

   public void publish(Path staging, String id) throws IOException {
      Objects.requireNonNull(staging, "staging");
      Path stagingRoot = this.root().resolve(".staging").toAbsolutePath().normalize();
      Path normalizedStaging = staging.toAbsolutePath().normalize();
      if (!normalizedStaging.startsWith(stagingRoot) || normalizedStaging.equals(stagingRoot)) {
         throw new IOException("Terrain preload staging directory is outside the cache root");
      }
      Path target = this.publishedDirectory(id);
      deleteTree(target);
      Files.createDirectories(target.getParent());
      try {
         Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException atomicMoveError) {
         Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
      }

      TellusCacheRegistry.clear(TellusCacheDomain.PRELOADED_TERRAIN);
      TerrainPreloadPackageRegistry.instance().invalidate();
   }

   public void deletePackage(String id) {
      try {
         deleteTree(this.publishedDirectory(id));
         TellusCacheRegistry.clear(TellusCacheDomain.PRELOADED_TERRAIN);
         TerrainPreloadPackageRegistry.instance().invalidate();
      } catch (IOException error) {
         Tellus.LOGGER.warn("Failed to delete preloaded terrain record {}", id, error);
      }
   }

   public void deleteAll() {
      try {
         deleteTree(this.root());
         TellusCacheRegistry.clear(TellusCacheDomain.PRELOADED_TERRAIN);
         TerrainPreloadPackageRegistry.instance().invalidate();
      } catch (IOException error) {
         Tellus.LOGGER.warn("Failed to delete preloaded terrain cache", error);
      }
   }

   public List<TerrainPreloadManifest> listManifests() {
      Path root = this.root();
      if (!Files.isDirectory(root)) {
         return List.of();
      }

      List<TerrainPreloadManifest> manifests = new ArrayList<>();
      try {
         try (var stream = Files.list(root)) {
            stream.filter(dir -> Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)).forEach(dir -> {
               Path manifestPath = dir.resolve("manifest.json");
               if (Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
                  try {
                     if (Files.size(manifestPath) > MAX_MANIFEST_BYTES) {
                        Tellus.LOGGER.warn("Ignoring oversized preloaded terrain manifest {}", manifestPath);
                        return;
                     }
                  } catch (IOException error) {
                     Tellus.LOGGER.warn("Failed to inspect preloaded terrain manifest {}", manifestPath, error);
                     return;
                  }
                  try (BufferedReader reader = Files.newBufferedReader(manifestPath, StandardCharsets.UTF_8)) {
                     TerrainPreloadManifest manifest = GSON.fromJson(reader, TerrainPreloadManifest.class);
                     if (manifest != null) {
                        manifests.add(manifest);
                     }
                  } catch (Exception error) {
                     Tellus.LOGGER.warn("Failed to read preloaded terrain manifest {}", manifestPath, error);
                  }
               }
            });
         }
      } catch (IOException error) {
         Tellus.LOGGER.warn("Failed to list preloaded terrain cache", error);
      }

      return List.copyOf(manifests);
   }

   public void writeManifest(Path directory, TerrainPreloadManifest manifest) throws IOException {
      Files.createDirectories(directory);
      Path manifestPath = directory.resolve("manifest.json");
      TellusCacheFiles.writeStringIfCurrent(
         null, 0L, manifestPath, GSON.toJson(Objects.requireNonNull(manifest, "manifest")), StandardCharsets.UTF_8
      );
   }

   public BufferedWriter openChunkIndex(Path directory) throws IOException {
      Files.createDirectories(directory);
      return Files.newBufferedWriter(directory.resolve("chunks.csv"), StandardCharsets.UTF_8);
   }

   public JsonElement encodeSettings(EarthGeneratorSettings settings) {
      return EarthGeneratorSettings.CODEC.encodeStart(JsonOps.INSTANCE, Objects.requireNonNull(settings, "settings"))
         .resultOrPartial(message -> Tellus.LOGGER.warn("Failed to encode preload settings: {}", message))
         .orElse(JsonNull.INSTANCE);
   }

   public String settingsFingerprint(JsonElement settingsJson) {
      try {
         MessageDigest digest = MessageDigest.getInstance("SHA-256");
         byte[] hash = digest.digest(GSON.toJson(canonicalFingerprintSettings(settingsJson)).getBytes(StandardCharsets.UTF_8));
         return HexFormat.of().formatHex(hash);
      } catch (NoSuchAlgorithmException error) {
         throw new IllegalStateException("SHA-256 unavailable", error);
      }
   }

   private static JsonElement canonicalFingerprintSettings(JsonElement settingsJson) {
      JsonElement canonical = Objects.requireNonNull(settingsJson, "settingsJson").deepCopy();
      if (canonical instanceof JsonObject object) {
         removeDefaultFalse(object, "world_scale_at_spawn");
         removeDefaultFalse(object, "center_world_on_spawn");
      }
      return canonical;
   }

   private static void removeDefaultFalse(JsonObject object, String field) {
      JsonElement value = object.get(field);
      if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && !value.getAsBoolean()) {
         object.remove(field);
      }
   }

   public long sizeBytes() {
      return sizeBytes(this.root());
   }

   public static long sizeBytes(Path root) {
      if (!Files.exists(root)) {
         return 0L;
      }

      final long[] total = new long[1];
      try {
         Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
               total[0] += attrs.size();
               return FileVisitResult.CONTINUE;
            }
         });
      } catch (IOException error) {
         Tellus.LOGGER.warn("Failed to scan preloaded terrain cache {}", root, error);
      }

      return total[0];
   }

   public static void deleteTree(Path root) throws IOException {
      if (root == null || !Files.exists(root)) {
         return;
      }

      Files.walkFileTree(root, new SimpleFileVisitor<>() {
         @Override
         public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            Files.deleteIfExists(file);
            return FileVisitResult.CONTINUE;
         }

         @Override
         public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
            if (error != null) {
               throw error;
            }

            Files.deleteIfExists(dir);
            return FileVisitResult.CONTINUE;
         }
      });
   }

   static boolean isValidIdentifier(String id) {
      if (id == null || id.isEmpty() || id.length() > 128) {
         return false;
      }
      for (int index = 0; index < id.length(); index++) {
         char character = id.charAt(index);
         if (!(character >= 'a' && character <= 'z')
            && !(character >= 'A' && character <= 'Z')
            && !(character >= '0' && character <= '9')
            && character != '-'
            && character != '_'
            && character != '.') {
            return false;
         }
      }
      return !id.equals(".") && !id.equals("..");
   }

   private static void requireValidIdentifier(String id) {
      if (!isValidIdentifier(id)) {
         throw new IllegalArgumentException("Invalid terrain preload identifier");
      }
   }
}
