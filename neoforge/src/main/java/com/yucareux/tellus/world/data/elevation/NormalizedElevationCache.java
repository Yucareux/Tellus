package com.yucareux.tellus.world.data.elevation;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.cache.TellusCacheDomain;
import com.yucareux.tellus.cache.TellusCacheHandle;
import com.yucareux.tellus.cache.TellusCacheRegistry;
import com.yucareux.tellus.world.data.elevation.TellusElevationSource.DemUsage;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.EarthProjection;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import net.neoforged.fml.loading.FMLPaths;
import net.minecraft.util.Mth;

final class NormalizedElevationCache implements TellusCacheHandle {
   private static final int DEFAULT_MEMORY_TILES = intProperty("tellus.elevation.normalized.memoryTiles", 256);
   private static final int DEFAULT_THREADS = intProperty(
      "tellus.elevation.normalized.threads",
      Math.max(1, Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() / 2)))
   );
   private final Path root;
   private final Cache<NormalizedElevationTileKey, NormalizedElevationTile> memoryCache;
   private final ExecutorService builderExecutor;
   private final ConcurrentHashMap<NormalizedElevationTileKey, CompletableFuture<NormalizedElevationTile>> inFlight = new ConcurrentHashMap<>();

   NormalizedElevationCache() {
      this(
         FMLPaths.GAMEDIR.get().resolve("tellus/cache/elevation-normalized"),
         Executors.newFixedThreadPool(DEFAULT_THREADS, new BuilderThreadFactory()),
         DEFAULT_MEMORY_TILES,
         true
      );
   }

   NormalizedElevationCache(Path baseRoot, ExecutorService builderExecutor, int maxMemoryTiles) {
      this(baseRoot, builderExecutor, maxMemoryTiles, false);
   }

   private NormalizedElevationCache(Path baseRoot, ExecutorService builderExecutor, int maxMemoryTiles, boolean registerHandle) {
      this.root = Objects.requireNonNull(baseRoot, "baseRoot").resolve(EarthProjection.projectionModeId()).resolve("v1");
      this.builderExecutor = Objects.requireNonNull(builderExecutor, "builderExecutor");
      this.memoryCache = CacheBuilder.newBuilder().maximumSize(Math.max(1, maxMemoryTiles)).build();
      if (registerHandle) {
         TellusCacheRegistry.register(this);
      }
   }

   NormalizedElevationTileSample sample(
      double projectedX,
      double projectedZ,
      double resolutionMeters,
      EarthGeneratorSettings.DemSelection demSelection,
      boolean highResOcean,
      NormalizedElevationCache.TileBuilder builder
   ) {
      NormalizedElevationTileKey key = NormalizedElevationTileKey.forProjectedMeters(projectedX, projectedZ, resolutionMeters, demSelection, highResOcean);
      double spacing = key.sampleResolutionMeters();
      double sampleX = projectedX / spacing;
      double sampleZ = projectedZ / spacing;
      int x0 = Mth.floor(sampleX);
      int z0 = Mth.floor(sampleZ);
      int x1 = x0 + 1;
      int z1 = z0 + 1;
      double dx = sampleX - x0;
      double dz = sampleZ - z0;
      NormalizedElevationTile baseTile = this.getOrBuildBlocking(key, builder);
      double v00 = this.sampleHeight(key, baseTile, x0, z0, builder);
      double v10 = this.sampleHeight(key, baseTile, x1, z0, builder);
      double v01 = this.sampleHeight(key, baseTile, x0, z1, builder);
      double v11 = this.sampleHeight(key, baseTile, x1, z1, builder);
      double lerpX0 = Mth.lerp(dx, v00, v10);
      double lerpX1 = Mth.lerp(dx, v01, v11);
      double elevation = Mth.lerp(dz, lerpX0, lerpX1);
      int nearestSampleX = Mth.floor(sampleX + 0.5);
      int nearestSampleZ = Mth.floor(sampleZ + 0.5);
      TileSample provenanceSample = this.sampleProvenance(key, baseTile, nearestSampleX, nearestSampleZ, builder);
      return new NormalizedElevationTileSample(elevation, provenanceSample.primaryProvider(), provenanceSample.providerMask(), spacing);
   }

   void prefetchRange(
      double minProjectedX,
      double minProjectedZ,
      double maxProjectedX,
      double maxProjectedZ,
      double resolutionMeters,
      EarthGeneratorSettings.DemSelection demSelection,
      boolean highResOcean,
      NormalizedElevationCache.TileBuilder builder
   ) {
      NormalizedElevationTileKey origin = NormalizedElevationTileKey.forProjectedMeters(
         minProjectedX, minProjectedZ, resolutionMeters, demSelection, highResOcean
      );
      int tileSpanMeters = origin.tileSpanMeters();
      int minTileX = Mth.floor(Math.min(minProjectedX, maxProjectedX) / tileSpanMeters);
      int maxTileX = Mth.floor(Math.max(minProjectedX, maxProjectedX) / tileSpanMeters);
      int minTileZ = Mth.floor(Math.min(minProjectedZ, maxProjectedZ) / tileSpanMeters);
      int maxTileZ = Mth.floor(Math.max(minProjectedZ, maxProjectedZ) / tileSpanMeters);

      for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
         for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
            this.scheduleBuild(origin.withTile(tileX, tileZ), builder);
         }
      }
   }

   NormalizedElevationTile getOrBuildBlocking(NormalizedElevationTileKey key, NormalizedElevationCache.TileBuilder builder) {
      NormalizedElevationTile cached = this.memoryCache.getIfPresent(key);
      if (cached != null) {
         return cached;
      } else {
         try {
            return this.scheduleBuild(key, builder).join();
         } catch (CompletionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) {
               throw runtime;
            } else {
               throw new RuntimeException("Failed to build normalized elevation tile " + key, cause);
            }
         }
      }
   }

   Path root() {
      return this.root;
   }

   @Override
   public TellusCacheDomain cacheDomain() {
      return TellusCacheDomain.NORMALIZED_TERRAIN;
   }

   @Override
   public void clearCache() {
      this.memoryCache.invalidateAll();
      this.memoryCache.cleanUp();
      for (CompletableFuture<NormalizedElevationTile> future : this.inFlight.values()) {
         future.cancel(true);
      }

      this.inFlight.clear();
   }

   private CompletableFuture<NormalizedElevationTile> scheduleBuild(NormalizedElevationTileKey key, NormalizedElevationCache.TileBuilder builder) {
      NormalizedElevationTile cached = this.memoryCache.getIfPresent(key);
      if (cached != null) {
         return CompletableFuture.completedFuture(cached);
      } else {
         return this.inFlight.computeIfAbsent(
            key,
            missing -> CompletableFuture.supplyAsync(() -> this.loadOrBuild(missing, builder), this.builderExecutor).whenComplete((tile, error) -> {
                  this.inFlight.remove(missing);
                  if (error == null && tile != null) {
                     this.memoryCache.put(missing, tile);
                  }
               })
         );
      }
   }

   private NormalizedElevationTile loadOrBuild(NormalizedElevationTileKey key, NormalizedElevationCache.TileBuilder builder) {
      NormalizedElevationTile diskTile = this.readTile(key);
      if (diskTile != null) {
         return diskTile;
      } else {
         try {
            NormalizedElevationTile built = Objects.requireNonNull(builder.build(key), "normalizedElevationTile");
            this.writeTile(key, built);
            return built;
         } catch (IOException error) {
            throw new RuntimeException("Failed to build normalized elevation tile " + key, error);
         }
      }
   }

   private NormalizedElevationTile readTile(NormalizedElevationTileKey key) {
      Path heightPath = this.heightPath(key);
      Path provenancePath = this.provenancePath(key);
      if (!Files.exists(heightPath) || !Files.exists(provenancePath)) {
         return null;
      } else {
         try (InputStream heightIn = Files.newInputStream(heightPath); InputStream provenanceIn = Files.newInputStream(provenancePath)) {
            ShortRaster heights = TellusRasterReader.readShortRaster(heightIn);
            TellusElevationProvenance provenance = TellusElevationProvenanceCodec.read(provenanceIn);
            return new NormalizedElevationTile(key, heights, provenance);
         } catch (IOException | RuntimeException error) {
            this.deleteCorruptTile(heightPath, provenancePath, key, error);
            return null;
         }
      }
   }

   private void writeTile(NormalizedElevationTileKey key, NormalizedElevationTile tile) throws IOException {
      Path heightPath = this.heightPath(key);
      Path provenancePath = this.provenancePath(key);
      Files.createDirectories(heightPath.getParent());
      Files.createDirectories(provenancePath.getParent());
      Path tempHeight = heightPath.resolveSibling(heightPath.getFileName() + ".tmp");
      Path tempProvenance = provenancePath.resolveSibling(provenancePath.getFileName() + ".tmp");

      try {
         try (OutputStream heightOut = Files.newOutputStream(tempHeight)) {
            TellusRasterWriter.writeShortRaster(heightOut, tile.heights());
         }

         try (OutputStream provenanceOut = Files.newOutputStream(tempProvenance)) {
            TellusElevationProvenanceCodec.write(provenanceOut, tile.provenance());
         }

         moveAtomically(tempHeight, heightPath);
         moveAtomically(tempProvenance, provenancePath);
      } catch (IOException error) {
         Files.deleteIfExists(tempHeight);
         Files.deleteIfExists(tempProvenance);
         throw error;
      }
   }

   private double sampleHeight(NormalizedElevationTileKey baseKey, int globalSampleX, int globalSampleZ, NormalizedElevationCache.TileBuilder builder) {
      int tileX = Math.floorDiv(globalSampleX, NormalizedElevationTileKey.TILE_SIZE);
      int tileZ = Math.floorDiv(globalSampleZ, NormalizedElevationTileKey.TILE_SIZE);
      int localX = Math.floorMod(globalSampleX, NormalizedElevationTileKey.TILE_SIZE);
      int localZ = Math.floorMod(globalSampleZ, NormalizedElevationTileKey.TILE_SIZE);
      NormalizedElevationTile tile = this.getOrBuildBlocking(baseKey.withTile(tileX, tileZ), builder);
      return tile.heights().get(localX, localZ);
   }

   private double sampleHeight(
      NormalizedElevationTileKey baseKey,
      NormalizedElevationTile baseTile,
      int globalSampleX,
      int globalSampleZ,
      NormalizedElevationCache.TileBuilder builder
   ) {
      if (isSampleInTile(baseKey, globalSampleX, globalSampleZ)) {
         return baseTile.heights().get(localSampleCoordinate(baseKey.tileX(), globalSampleX), localSampleCoordinate(baseKey.tileZ(), globalSampleZ));
      }

      return this.sampleHeight(baseKey, globalSampleX, globalSampleZ, builder);
   }

   private TileSample sampleProvenance(
      NormalizedElevationTileKey baseKey, int globalSampleX, int globalSampleZ, NormalizedElevationCache.TileBuilder builder
   ) {
      int tileX = Math.floorDiv(globalSampleX, NormalizedElevationTileKey.TILE_SIZE);
      int tileZ = Math.floorDiv(globalSampleZ, NormalizedElevationTileKey.TILE_SIZE);
      int localX = Math.floorMod(globalSampleX, NormalizedElevationTileKey.TILE_SIZE);
      int localZ = Math.floorMod(globalSampleZ, NormalizedElevationTileKey.TILE_SIZE);
      NormalizedElevationTile tile = this.getOrBuildBlocking(baseKey.withTile(tileX, tileZ), builder);
      TellusElevationProvenance provenance = tile.provenance();
      DemUsage primaryProvider = provenance.primaryProvider(localX, localZ);
      int providerMask = provenance.isBlended(localX, localZ) ? provenance.providerMask() : primaryProvider.bit();
      return new TileSample(primaryProvider, providerMask);
   }

   private TileSample sampleProvenance(
      NormalizedElevationTileKey baseKey,
      NormalizedElevationTile baseTile,
      int globalSampleX,
      int globalSampleZ,
      NormalizedElevationCache.TileBuilder builder
   ) {
      if (isSampleInTile(baseKey, globalSampleX, globalSampleZ)) {
         int localX = localSampleCoordinate(baseKey.tileX(), globalSampleX);
         int localZ = localSampleCoordinate(baseKey.tileZ(), globalSampleZ);
         TellusElevationProvenance provenance = baseTile.provenance();
         DemUsage primaryProvider = provenance.primaryProvider(localX, localZ);
         int providerMask = provenance.isBlended(localX, localZ) ? provenance.providerMask() : primaryProvider.bit();
         return new TileSample(primaryProvider, providerMask);
      }

      return this.sampleProvenance(baseKey, globalSampleX, globalSampleZ, builder);
   }

   private Path heightPath(NormalizedElevationTileKey key) {
      return this.resolveTileDirectory(key).resolve(Integer.toString(key.tileZ()) + ".raster");
   }

   private Path provenancePath(NormalizedElevationTileKey key) {
      return this.resolveTileDirectory(key).resolve(Integer.toString(key.tileZ()) + ".prov");
   }

   private Path resolveTileDirectory(NormalizedElevationTileKey key) {
      String oceanMode = key.highResOcean() ? "highres-ocean" : "standard-ocean";
      return this.root.resolve(key.demSelectionFingerprint()).resolve(oceanMode).resolve("lod" + key.lod()).resolve(Integer.toString(key.tileX()));
   }

   private void deleteCorruptTile(Path heightPath, Path provenancePath, NormalizedElevationTileKey key, Throwable error) {
      try {
         Files.deleteIfExists(heightPath);
      } catch (IOException ignored) {
      }

      try {
         Files.deleteIfExists(provenancePath);
      } catch (IOException ignored) {
      }

      Tellus.LOGGER.debug("Rebuilding corrupt normalized elevation tile {}", key, error);
   }

   private static void moveAtomically(Path source, Path target) throws IOException {
      try {
         Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException ignored) {
         Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
      }
   }

   private static int intProperty(String key, int defaultValue) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      } else {
         try {
            return Math.max(1, Integer.parseInt(value));
         } catch (NumberFormatException ignored) {
            return defaultValue;
         }
      }
   }

   private static boolean isSampleInTile(NormalizedElevationTileKey key, int globalSampleX, int globalSampleZ) {
      return Math.floorDiv(globalSampleX, NormalizedElevationTileKey.TILE_SIZE) == key.tileX()
         && Math.floorDiv(globalSampleZ, NormalizedElevationTileKey.TILE_SIZE) == key.tileZ();
   }

   private static int localSampleCoordinate(int tileCoordinate, int globalSampleCoordinate) {
      return globalSampleCoordinate - tileCoordinate * NormalizedElevationTileKey.TILE_SIZE;
   }

   @FunctionalInterface
   interface TileBuilder {
      NormalizedElevationTile build(NormalizedElevationTileKey key) throws IOException;
   }

   private static record TileSample(DemUsage primaryProvider, int providerMask) {
   }

   private static final class BuilderThreadFactory implements ThreadFactory {
      private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

      @Override
      public Thread newThread(Runnable task) {
         Thread thread = new Thread(task, "Tellus-NormalizedElevation-" + NEXT_ID.getAndIncrement());
         thread.setDaemon(true);
         return thread;
      }
   }
}
