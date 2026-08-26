package com.yucareux.tellus.world.data.elevation;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.cache.TellusCacheDomain;
import com.yucareux.tellus.cache.TellusCacheFiles;
import com.yucareux.tellus.cache.TellusCacheHandle;
import com.yucareux.tellus.cache.TellusCacheRegistry;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.world.data.mask.TellusLandMaskSource;
import com.yucareux.tellus.world.data.source.DownloadProgressReporter;
import com.yucareux.tellus.world.data.source.ParallelDownloadRunner;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.WorldProjection;
import com.yucareux.tellus.worldgen.TerrainSlopePolicy;
import com.yucareux.tellus.worldgen.TellusWorldgenSources;
import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import com.yucareux.tellus.platform.TellusPlatform;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

public final class TellusElevationSource implements TellusCacheHandle {
   private static final double EQUATOR_CIRCUMFERENCE = 4.0075017E7;
   private static final int TILE_SIZE = 512;
   private static final int TERRARIUM_ENCODING_SCALE = 256;
   private static final int MIN_ZOOM = 0;
   private static final int LAND_MAX_ZOOM = 18;
   private static final int MAPTERHORN_GLOBAL_FALLBACK_ZOOM = 12;
   private static final int MAPTERHORN_OCEAN_FALLBACK_ZOOM = 10;
   private static final int OCEAN_MAX_ZOOM = 17;
   private static final int OPENWATERS_GLOBAL_FALLBACK_ZOOM = 8;
   private static final double MIN_LAT = -85.05112878;
   private static final double MAX_LAT = 85.05112878;
   private static final double MIN_LON = -180.0;
   private static final double MAX_LON = 180.0;
   private static final double RESOLUTION_METERS = 30.0;
   private static final double OPENWATERS_NOMINAL_RESOLUTION_METERS = 463.0;
   public static final double MAPTERHORN_SEA_LEVEL_THRESHOLD_METERS = 0.0;
   private static final String MAPTERHORN_ENDPOINT = "https://tiles.mapterhorn.com";
   private static final String OPENWATERS_ENDPOINT = "https://tiles.openwaters.io/seascape";
   private static final int MAX_CACHE_TILES = intProperty("tellus.elevation.cacheTiles", 512);
   private static final int AREA_PREFETCH_SAMPLE_POINTS = intProperty("tellus.elevation.areaPrefetch.samples", 25);
   private static final int AREA_PREFETCH_TERRAIN_TILE_LIMIT = intProperty("tellus.elevation.areaPrefetch.terrainTileLimit", 512);
   private static final int TILE_DOWNLOAD_ATTEMPTS = intProperty("tellus.elevation.downloadAttempts", 3);
   private static final int TILE_CONNECT_TIMEOUT_MS = intProperty("tellus.elevation.connectTimeoutMs", 8000);
   private static final int TILE_READ_TIMEOUT_MS = intProperty("tellus.elevation.readTimeoutMs", 8000);
   private static final int TILE_RETRY_BACKOFF_MS = intProperty("tellus.elevation.retryBackoffMs", 250);
   private static final int MAX_TILE_BYTES = 8 * 1024 * 1024;
   // The normalized cache currently incurs a very expensive first-build path on cache misses.
   // Keep it opt-in until the ingest/build cost is low enough for preview and spawn-time terrain reads.
   private static final boolean NORMALIZED_ENABLED = booleanProperty("tellus.elevation.normalized.enabled", false);
   private static final boolean NORMALIZED_COMPARE = booleanProperty("tellus.elevation.normalized.compare", false);
   private static final int NORMALIZED_COMPARE_LOG_LIMIT = intProperty("tellus.elevation.normalized.compareLogLimit", 100);
   private static final boolean DEBUG_DEM = Boolean.getBoolean("tellus.debug.dem");
   private static final ShortRaster MISSING_RASTER = ShortRaster.create(1, 1);
   private static final ShortRaster ZERO_RASTER = ShortRaster.create(TILE_SIZE, TILE_SIZE);
   private static final WebPImageReaderSpi WEBP_READER_SPI = new WebPImageReaderSpi();
   private static final NormalizedElevationCache NORMALIZED_CACHE = NORMALIZED_ENABLED ? new NormalizedElevationCache() : null;
   private static final AtomicInteger NORMALIZED_COMPARE_LOGGED = new AtomicInteger();
   private final Path cacheRoot;
   private final Path oceanCacheRoot;
   private final LoadingCache<TellusElevationSource.TileKey, ShortRaster> cache;
   private final LoadingCache<TellusElevationSource.TileKey, ShortRaster> oceanCache;
   private final MapterhornCoverageResolutionSource mapterhornResolutionSource = new MapterhornCoverageResolutionSource();
   private final TellusLandMaskSource landMask = TellusWorldgenSources.landMask();
   private volatile EarthGeneratorSettings.DemSelection lastLoggedSelection;

   public TellusElevationSource() {
      this.cacheRoot = TellusPlatform.gameDir().resolve("tellus/cache/elevation-mapterhorn");
      this.oceanCacheRoot = TellusPlatform.gameDir().resolve("tellus/cache/elevation-openwaters");
      this.cache = CacheBuilder.newBuilder().maximumSize(MAX_CACHE_TILES).build(new CacheLoader<TellusElevationSource.TileKey, ShortRaster>() {
         public ShortRaster load( TellusElevationSource.TileKey key) throws Exception {
            return TellusElevationSource.this.loadTile(key);
         }
      });
      this.oceanCache = CacheBuilder.newBuilder().maximumSize(MAX_CACHE_TILES).build(new CacheLoader<TellusElevationSource.TileKey, ShortRaster>() {
         public ShortRaster load( TellusElevationSource.TileKey key) throws Exception {
            return TellusElevationSource.this.loadOpenWatersTile(key);
         }
      });
      TellusCacheRegistry.register(this);
   }

   public double sampleElevationMeters(double blockX, double blockZ, WorldProjection projection) {
      return this.sampleElevationMeters(blockX, blockZ, projection, false, EarthGeneratorSettings.DEFAULT.demSelection());
   }

   public double sampleElevationMeters(double blockX, double blockZ, WorldProjection projection, boolean highResOcean) {
      return this.sampleElevationMeters(blockX, blockZ, projection, highResOcean, EarthGeneratorSettings.DEFAULT.demSelection());
   }

   public double sampleElevationMeters(
      double blockX,
      double blockZ,
      WorldProjection projection,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection
   ) {
      if (ManagedTerrainNetworkPolicy.isCacheOnly()) {
         return this.samplePreviewElevationMetersLocalOnly(
            blockX, blockZ, projection, highResOcean, demSelection, projection.worldScale()
         );
      }
      return this.sampleElevationMeters(blockX, blockZ, projection, highResOcean, demSelection, projection.worldScale());
   }

   public TellusElevationSource.ResolvedElevationSample sampleResolvedElevationMeters(
      double blockX,
      double blockZ,
      WorldProjection projection,
      boolean oceanMask,
      EarthGeneratorSettings.DemSelection demSelection
   ) {
      if (ManagedTerrainNetworkPolicy.isCacheOnly()) {
         return this.sampleResolvedPreviewElevationMetersLocalOnly(blockX, blockZ, projection, oceanMask, demSelection, projection.worldScale());
      }
      return this.sampleResolvedElevation(
         blockX, blockZ, projection, oceanMask, demSelection, projection.worldScale()
      );
   }

   /**
    * Returns the native Mapterhorn source spacing advertised at a location.
    * Water fitting uses this once per cached region to choose conservative
    * polygon-correction thresholds; it does not add a per-block lookup.
    */
   public double mapterhornSourceResolutionMeters(
      double blockX, double blockZ, WorldProjection projection, double requestedResolutionMeters
   ) {
      return this.terrainTilesResolutionMeters(
         blockX, blockZ, projection, requestedResolutionMeters
      );
   }

   public double samplePreviewElevationMeters(
      double blockX,
      double blockZ,
      WorldProjection projection,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      if (ManagedTerrainNetworkPolicy.isCacheOnly()) {
         return this.samplePreviewElevationMetersLocalOnly(
            blockX, blockZ, projection, highResOcean, demSelection, previewResolutionMeters
         );
      }
      if (NORMALIZED_ENABLED) {
         return this.sampleResolvedPreviewElevationMeters(
            blockX, blockZ, projection, highResOcean, demSelection, previewResolutionMeters
         ).elevationMeters();
      }

      return projection.worldScale() > 0.0
         ? this.samplePreviewElevationMetersFromProviders(
            blockX, blockZ, projection, highResOcean, demSelection, previewResolutionMeters
         )
         : Double.NaN;
   }

   public TellusElevationSource.ResolvedElevationSample sampleResolvedPreviewElevationMeters(
      double blockX,
      double blockZ,
      WorldProjection projection,
      boolean oceanMask,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      if (ManagedTerrainNetworkPolicy.isCacheOnly()) {
         return this.sampleResolvedPreviewElevationMetersLocalOnly(
            blockX, blockZ, projection, oceanMask, demSelection, previewResolutionMeters
         );
      }
      TellusElevationSource.ResolvedElevationSample sample = this.sampleResolvedElevation(
         blockX, blockZ, projection, oceanMask, demSelection, previewResolutionMeters
      );
      return isUsablePreviewElevation(sample) ? sample : missingResolvedElevation(Double.NaN);
   }

   public double samplePreviewElevationMetersLocalOnly(
      double blockX,
      double blockZ,
      WorldProjection projection,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      return projection.worldScale() > 0.0
         ? this.samplePreviewElevationMetersFromProvidersLocalOnly(
            blockX, blockZ, projection, highResOcean, previewResolutionMeters
         )
         : Double.NaN;
   }

   public TellusElevationSource.ResolvedElevationSample sampleResolvedPreviewElevationMetersLocalOnly(
      double blockX,
      double blockZ,
      WorldProjection projection,
      boolean oceanMask,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      return projection.worldScale() <= 0.0
         ? missingResolvedElevation(Double.NaN)
         : requireUsablePreviewElevation(
            this.sampleResolvedElevationFromProvidersLocalOnly(
               blockX, blockZ, projection, oceanMask, demSelection, previewResolutionMeters
            )
         );
   }

   public double samplePreviewElevationMetersMemoryOnly(
      double blockX,
      double blockZ,
      WorldProjection projection,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      return projection.worldScale() > 0.0
         ? this.samplePreviewElevationMetersFromProvidersMemoryOnly(
            blockX, blockZ, projection, highResOcean, previewResolutionMeters
         )
         : Double.NaN;
   }

   public double sampleTerrainSlopeDegreesLocalOnly(double blockX, double blockZ, WorldProjection projection) {
      if (!(projection.worldScale() > 0.0)) {
         return Double.NaN;
      }

      double groundMetersPerBlockX = projection.groundMetersPerBlockX(blockZ);
      double groundMetersPerBlockZ = projection.groundMetersPerBlockZ(blockZ);
      if (!(groundMetersPerBlockX > 0.0) || !(groundMetersPerBlockZ > 0.0)) {
         return Double.NaN;
      }

      double sampleResolutionMeters = projection.worldScale();
      int stepX = TerrainSlopePolicy.SAMPLE_RADIUS_BLOCKS;
      int stepZ = TerrainSlopePolicy.SAMPLE_RADIUS_BLOCKS;
      double center = this.sampleTerrainTilesMetersLocalOnlyOrMissing(
         blockX, blockZ, projection, sampleResolutionMeters
      );
      double east = this.sampleTerrainTilesMetersLocalOnlyOrMissing(
         blockX + stepX, blockZ, projection, sampleResolutionMeters
      );
      double west = this.sampleTerrainTilesMetersLocalOnlyOrMissing(
         blockX - stepX, blockZ, projection, sampleResolutionMeters
      );
      double north = this.sampleTerrainTilesMetersLocalOnlyOrMissing(
         blockX, blockZ - stepZ, projection, sampleResolutionMeters
      );
      double south = this.sampleTerrainTilesMetersLocalOnlyOrMissing(
         blockX, blockZ + stepZ, projection, sampleResolutionMeters
      );
      return TerrainSlopePolicy.localSlopeDegrees(
         center,
         east,
         west,
         north,
         south,
         stepX * groundMetersPerBlockX,
         stepZ * groundMetersPerBlockZ
      );
   }

   public TellusElevationSource.ResolvedElevationSample sampleResolvedPreviewElevationMetersMemoryOnly(
      double blockX,
      double blockZ,
      WorldProjection projection,
      boolean oceanMask,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      return projection.worldScale() <= 0.0
         ? missingResolvedElevation(Double.NaN)
         : requireUsablePreviewElevation(
            this.sampleResolvedElevationFromProvidersMemoryOnly(
               blockX, blockZ, projection, oceanMask, demSelection, previewResolutionMeters
            )
         );
   }

   public double sampleOceanElevationMeters(double blockX, double blockZ, WorldProjection projection) {
      return this.sampleOceanElevationMeters(blockX, blockZ, projection, EarthGeneratorSettings.DEFAULT.demSelection());
   }

   public double sampleOceanElevationMeters(
      double blockX, double blockZ, WorldProjection projection, EarthGeneratorSettings.DemSelection demSelection
   ) {
      if (ManagedTerrainNetworkPolicy.isCacheOnly()) {
         return this.samplePreviewOceanElevationMetersLocalOnly(
            blockX, blockZ, projection, demSelection, projection.worldScale()
         );
      }
      return this.sampleOceanElevation(blockX, blockZ, projection, demSelection, projection.worldScale()).elevation();
   }

   public double samplePreviewOceanElevationMeters(double blockX, double blockZ, WorldProjection projection, double previewResolutionMeters) {
      return this.samplePreviewOceanElevationMeters(
         blockX, blockZ, projection, EarthGeneratorSettings.DEFAULT.demSelection(), previewResolutionMeters
      );
   }

   public double samplePreviewOceanElevationMeters(
      double blockX,
      double blockZ,
      WorldProjection projection,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      if (ManagedTerrainNetworkPolicy.isCacheOnly()) {
         return this.samplePreviewOceanElevationMetersLocalOnly(
            blockX, blockZ, projection, demSelection, previewResolutionMeters
         );
      }
      return this.sampleOceanElevation(blockX, blockZ, projection, demSelection, previewResolutionMeters).elevation();
   }

   /**
    * Samples only the OpenWaters Terrarium raster. A non-finite result means that
    * OpenWaters was unavailable at every supported fallback zoom; no land DEM is
    * substituted by this method.
    */
   public double sampleOpenWatersElevationMeters(
      double blockX, double blockZ, WorldProjection projection, double requestedResolutionMeters
   ) {
      if (!(projection.worldScale() > 0.0)) {
         return Double.NaN;
      }

      return ManagedTerrainNetworkPolicy.isCacheOnly()
         ? this.sampleOpenWatersBathymetryLocalOnly(blockX, blockZ, projection, requestedResolutionMeters)
         : this.sampleOpenWatersBathymetry(blockX, blockZ, projection, requestedResolutionMeters);
   }

   public double samplePreviewOceanElevationMetersLocalOnly(double blockX, double blockZ, WorldProjection projection, double previewResolutionMeters) {
      return this.samplePreviewOceanElevationMetersLocalOnly(
         blockX, blockZ, projection, EarthGeneratorSettings.DEFAULT.demSelection(), previewResolutionMeters
      );
   }

   public double samplePreviewOceanElevationMetersLocalOnly(
      double blockX,
      double blockZ,
      WorldProjection projection,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      if (projection.worldScale() <= 0.0) {
         return Double.NaN;
      }

      double openWaters = this.sampleOpenWatersBathymetryLocalOnly(blockX, blockZ, projection, previewResolutionMeters);
      if (Double.isFinite(openWaters)) {
         return openWaters;
      }

      return this.sampleTerrainTilesOceanFallbackLocalOnly(blockX, blockZ, projection, previewResolutionMeters);
   }

   public double samplePreviewOceanElevationMetersMemoryOnly(double blockX, double blockZ, WorldProjection projection, double previewResolutionMeters) {
      return this.samplePreviewOceanElevationMetersMemoryOnly(
         blockX, blockZ, projection, EarthGeneratorSettings.DEFAULT.demSelection(), previewResolutionMeters
      );
   }

   public double samplePreviewOceanElevationMetersMemoryOnly(
      double blockX,
      double blockZ,
      WorldProjection projection,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      if (projection.worldScale() <= 0.0) {
         return Double.NaN;
      }

      double openWaters = this.sampleOpenWatersBathymetryMemoryOnly(blockX, blockZ, projection, previewResolutionMeters);
      if (Double.isFinite(openWaters)) {
         return openWaters;
      }

      return this.sampleTerrainTilesOceanFallbackMemoryOnly(blockX, blockZ, projection, previewResolutionMeters);
   }

   public double samplePreviewTerrainTilesMetersLocalOnly(
      double blockX, double blockZ, WorldProjection projection, boolean highResOcean, double previewResolutionMeters
   ) {
      return this.sampleTerrariumMetersLocalOnly(blockX, blockZ, projection, highResOcean, previewResolutionMeters);
   }

   private double sampleElevationMeters(
      double blockX,
      double blockZ,
      WorldProjection projection,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      return this.sampleResolvedElevation(
         blockX, blockZ, projection, highResOcean, demSelection, previewResolutionMeters
      ).elevationMeters();
   }

   private TellusElevationSource.ResolvedElevationSample sampleResolvedElevation(
      double blockX,
      double blockZ,
      WorldProjection projection,
      boolean oceanMask,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      if (projection.worldScale() <= 0.0) {
         return missingResolvedElevation(0.0);
      } else {
         demSelection = lockedDemSelection(demSelection);
         if (DEBUG_DEM && !Objects.equals(demSelection, this.lastLoggedSelection)) {
            this.lastLoggedSelection = demSelection;
            Tellus.LOGGER.info(
               "DEM selection set to automatic={} providers={}.",
               demSelection.automatic(),
               String.join(",", demSelection.enabledProviderIds())
            );
         }

         if (NORMALIZED_ENABLED) {
            try {
               TellusElevationSource.ResolvedElevationSample normalized = this.sampleResolvedFromNormalizedCache(
                  blockX, blockZ, projection, oceanMask, demSelection, previewResolutionMeters
               );
               this.compareNormalizedElevation(
                  blockX, blockZ, projection, oceanMask, demSelection, previewResolutionMeters, normalized.elevationMeters()
               );
               return normalized;
            } catch (RuntimeException error) {
               Tellus.LOGGER.debug("Falling back to direct DEM sampling for normalized cache miss at {},{}", blockX, blockZ, error);
            }
         }

         return this.sampleResolvedElevationFromProviders(
            blockX, blockZ, projection, oceanMask, demSelection, previewResolutionMeters
         );
      }
   }

   private double sampleElevationMetersFromProviders(
      double blockX,
      double blockZ,
      WorldProjection projection,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      return this.sampleResolvedElevationFromProviders(
         blockX, blockZ, projection, highResOcean, demSelection, previewResolutionMeters
      ).elevationMeters();
   }

   private TellusElevationSource.ResolvedElevationSample sampleResolvedElevationFromProviders(
      double blockX,
      double blockZ,
      WorldProjection projection,
      boolean oceanMask,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      double mapterhorn = this.sampleTerrainTilesMetersOrMissing(blockX, blockZ, projection, previewResolutionMeters);
      return this.resolveElevationSample(
         mapterhorn,
         oceanMask,
         TellusElevationSource.DemUsage.TERRAIN_TILES.nominalResolutionMeters(),
         () -> this.sampleOceanElevation(blockX, blockZ, projection, lockedDemSelection(demSelection), previewResolutionMeters),
         0.0
      );
   }

   private double samplePreviewElevationMetersFromProviders(
      double blockX,
      double blockZ,
      WorldProjection projection,
      boolean oceanMask,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      double mapterhorn = this.sampleTerrainTilesMetersOrMissing(blockX, blockZ, projection, previewResolutionMeters);
      if (shouldUseBathymetry(oceanMask, mapterhorn)) {
         double ocean = this.sampleOceanElevation(
            blockX, blockZ, projection, lockedDemSelection(demSelection), previewResolutionMeters
         ).elevation();
         if (Double.isFinite(ocean)) {
            return ocean;
         }
      }

      return mapterhorn;
   }

   private TellusElevationSource.ResolvedElevationSample sampleResolvedElevationFromProvidersLocalOnly(
      double blockX,
      double blockZ,
      WorldProjection projection,
      boolean oceanMask,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      double mapterhorn = this.sampleTerrainTilesMetersLocalOnlyOrMissing(blockX, blockZ, projection, previewResolutionMeters);
      return this.resolveElevationSample(
         mapterhorn,
         oceanMask,
         TellusElevationSource.DemUsage.TERRAIN_TILES.nominalResolutionMeters(),
         () -> this.sampleOceanElevationLocalOnly(blockX, blockZ, projection, previewResolutionMeters),
         0.0
      );
   }

   private double samplePreviewElevationMetersFromProvidersLocalOnly(
      double blockX,
      double blockZ,
      WorldProjection projection,
      boolean oceanMask,
      double previewResolutionMeters
   ) {
      double mapterhorn = this.sampleTerrainTilesMetersLocalOnlyOrMissing(
         blockX, blockZ, projection, previewResolutionMeters
      );
      if (shouldUseBathymetry(oceanMask, mapterhorn)) {
         double ocean = this.sampleOceanElevationLocalOnly(
            blockX, blockZ, projection, previewResolutionMeters
         ).elevation();
         if (Double.isFinite(ocean)) {
            return ocean;
         }
      }

      return mapterhorn;
   }

   private TellusElevationSource.ResolvedElevationSample sampleResolvedElevationFromProvidersMemoryOnly(
      double blockX,
      double blockZ,
      WorldProjection projection,
      boolean oceanMask,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      double mapterhorn = this.sampleTerrainTilesMetersMemoryOnly(blockX, blockZ, projection, previewResolutionMeters);
      return this.resolveElevationSample(
         mapterhorn,
         oceanMask,
         TellusElevationSource.DemUsage.TERRAIN_TILES.nominalResolutionMeters(),
         () -> this.sampleOceanElevationMemoryOnly(blockX, blockZ, projection, previewResolutionMeters),
         Double.NaN
      );
   }

   private double samplePreviewElevationMetersFromProvidersMemoryOnly(
      double blockX,
      double blockZ,
      WorldProjection projection,
      boolean oceanMask,
      double previewResolutionMeters
   ) {
      double mapterhorn = this.sampleTerrainTilesMetersMemoryOnly(blockX, blockZ, projection, previewResolutionMeters);
      if (shouldUseBathymetry(oceanMask, mapterhorn)) {
         double ocean = this.sampleOceanElevationMemoryOnly(
            blockX, blockZ, projection, previewResolutionMeters
         ).elevation();
         if (Double.isFinite(ocean)) {
            return ocean;
         }
      }

      return mapterhorn;
   }

   public TellusElevationSource.ElevationDiagnostic sampleDiagnostic(
      double blockX, double blockZ, WorldProjection projection, boolean highResOcean, EarthGeneratorSettings.DemSelection demSelection
   ) {
      return this.sampleDiagnostic(blockX, blockZ, projection, highResOcean, demSelection, projection.worldScale());
   }

   public TellusElevationSource.ElevationDiagnostic samplePreviewDiagnostic(
      double blockX,
      double blockZ,
      WorldProjection projection,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      return this.sampleDiagnostic(blockX, blockZ, projection, highResOcean, demSelection, previewResolutionMeters);
   }

   public TellusElevationSource.ElevationDiagnostic sampleOceanDiagnostic(double blockX, double blockZ, WorldProjection projection) {
      TellusElevationSource.OceanElevationSample sample = this.sampleOceanElevation(
         blockX, blockZ, projection, EarthGeneratorSettings.DEFAULT.demSelection(), projection.worldScale()
      );
      return diagnostic(
         sample.elevation(),
         sample.usage(),
         sample.usage().bit(),
         sample.usage().nominalResolutionMeters()
      );
   }

   private TellusElevationSource.ElevationDiagnostic sampleDiagnostic(
      double blockX,
      double blockZ,
      WorldProjection projection,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      TellusElevationSource.ResolvedElevationSample sample = this.sampleResolvedElevation(
         blockX, blockZ, projection, highResOcean, demSelection, previewResolutionMeters
      );
      if (sample.primaryProvider() != TellusElevationSource.DemUsage.TERRAIN_TILES) {
         return diagnostic(sample);
      }
      return diagnostic(
         sample.elevationMeters(),
         sample.primaryProvider(),
         sample.providerMask(),
         this.terrainTilesResolutionMeters(blockX, blockZ, projection, previewResolutionMeters)
      );
   }

   public void prefetchTiles(double blockX, double blockZ, WorldProjection projection, int radius) {
      this.prefetchTiles(blockX, blockZ, projection, radius, EarthGeneratorSettings.DEFAULT.demSelection());
   }

   public void prefetchTiles(
      double blockX,
      double blockZ,
      WorldProjection projection,
      int radius,
      EarthGeneratorSettings.DemSelection demSelection
   ) {
      this.prefetchTiles(blockX, blockZ, projection, radius, demSelection, projection.worldScale());
   }

   public void prefetchTiles(
      double blockX,
      double blockZ,
      WorldProjection projection,
      int radius,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      if (!(projection.worldScale() <= 0.0)) {
         demSelection = lockedDemSelection(demSelection);
         if (NORMALIZED_ENABLED) {
            this.prefetchNormalizedTiles(blockX, blockZ, projection, radius, demSelection, previewResolutionMeters);
            return;
         }

         this.prefetchTerrainTiles(blockX, blockZ, projection, radius, previewResolutionMeters);
         this.prefetchOpenWatersTilesIfLikelyOcean(blockX, blockZ, projection, radius, previewResolutionMeters);
      }
   }

   public int preloadAreaTaskCount(
      double minBlockX,
      double minBlockZ,
      double maxBlockX,
      double maxBlockZ,
      WorldProjection projection,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      if (projection.worldScale() <= 0.0) {
         return 1;
      }
      return Math.max(1, this.downloadPlan(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, previewResolutionMeters).size());
   }

   public int preloadAreaInputs(
      double minBlockX,
      double minBlockZ,
      double maxBlockX,
      double maxBlockZ,
      WorldProjection projection,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters,
      int completedUnits,
      BiConsumer<Integer, String> progressConsumer
   ) {
      return this.preloadAreaInputs(
         minBlockX,
         minBlockZ,
         maxBlockX,
         maxBlockZ,
         projection,
         demSelection,
         previewResolutionMeters,
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
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters,
      int completedUnits,
      BiConsumer<Integer, String> progressConsumer
   ) {
      return this.preloadAreaInputs(
         minBlockX,
         minBlockZ,
         maxBlockX,
         maxBlockZ,
         projection,
         demSelection,
         previewResolutionMeters,
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
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters,
      int completedUnits,
      BiConsumer<Integer, String> progressConsumer,
      boolean loadIntoMemory
   ) {
      BiConsumer<Integer, String> progress = progressConsumer == null ? (completed, detail) -> {
      } : progressConsumer;
      if (projection.worldScale() <= 0.0) {
         progress.accept(completedUnits, "Skipping DEM elevation preload because world scale is invalid");
         return completedUnits + 1;
      }

      List<TellusElevationSource.RawTileRequest> plan = this.downloadPlan(
         minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, previewResolutionMeters
      );
      if (plan.isEmpty()) {
         progress.accept(completedUnits, "No DEM elevation tiles intersect the selected area");
         return completedUnits + 1;
      }

      int startingUnits = completedUnits;
      progress.accept(
         completedUnits,
         (loadIntoMemory ? "Loading " : "Downloading ") + plan.size() + " DEM source tiles with bounded parallelism"
      );
      return ParallelDownloadRunner.run(
         ParallelDownloadRunner.scope(
            loadIntoMemory ? "elevation-memory" : "elevation",
            TellusCacheRegistry.generation(TellusCacheDomain.TERRAIN),
            TellusCacheRegistry.generation(TellusCacheDomain.OPENWATERS)
         ),
         plan,
         completedUnits,
         request -> {
            if (loadIntoMemory) {
               this.loadTileIntoCache(request);
            } else if (request.openWaters()) {
               this.downloadOpenWatersTile(request.key());
            } else {
               this.downloadTerrainTile(request.key());
            }
         },
         (request, completed, phaseTotal) -> progress.accept(
            completed,
            (loadIntoMemory ? "Loaded " : "Cached ")
               + "DEM source tile "
               + (completed - startingUnits)
               + "/"
               + phaseTotal
               + " ("
               + request.label()
               + ")"
         )
      );
   }

   private List<TellusElevationSource.RawTileRequest> downloadPlan(
      double minBlockX,
      double minBlockZ,
      double maxBlockX,
      double maxBlockZ,
      WorldProjection projection,
      double previewResolutionMeters
   ) {
      if (projection.worldScale() <= 0.0) {
         return List.of();
      }

      LinkedHashSet<TellusElevationSource.RawTileRequest> plan = new LinkedHashSet<>();
      List<TellusElevationSource.AreaSample> samples = areaSamples(minBlockX, minBlockZ, maxBlockX, maxBlockZ);
      TellusElevationSource.TerrainTileBounds terrainBounds = this.terrainTileBoundsForArea(
         minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, previewResolutionMeters
      );
      if (this.shouldPrefetchExactTerrainArea(terrainBounds)) {
         for (int tileY = terrainBounds.minY(); tileY <= terrainBounds.maxY(); tileY++) {
            for (TellusElevationSource.TileXRange range : terrainBounds.xRanges()) {
               for (int tileX = range.minX(); tileX <= range.maxX(); tileX++) {
                  plan.add(
                     new TellusElevationSource.RawTileRequest(
                        new TellusElevationSource.TileKey(terrainBounds.zoom(), tileX, tileY), false
                     )
                  );
               }
            }
         }
         for (TellusElevationSource.AreaSample sample : samples) {
            this.addOpenWatersTiles(plan, sample.blockX(), sample.blockZ(), projection, 1, previewResolutionMeters);
         }
      } else {
         for (TellusElevationSource.AreaSample sample : samples) {
            this.addTerrainTiles(plan, sample.blockX(), sample.blockZ(), projection, 1, previewResolutionMeters);
            this.addOpenWatersTiles(plan, sample.blockX(), sample.blockZ(), projection, 1, previewResolutionMeters);
         }
      }
      return List.copyOf(plan);
   }

   private boolean shouldPrefetchExactTerrainArea(TellusElevationSource.TerrainTileBounds terrainBounds) {
      return terrainBounds != null && terrainBounds.count() <= AREA_PREFETCH_TERRAIN_TILE_LIMIT;
   }

   private TellusElevationSource.TerrainTileBounds terrainTileBoundsForArea(
      double minBlockX,
      double minBlockZ,
      double maxBlockX,
      double maxBlockZ,
      WorldProjection projection,
      double previewResolutionMeters
   ) {
      if (projection.worldScale() <= 0.0) {
         return null;
      }

      int step = downsampleStep(projection.worldScale(), RESOLUTION_METERS, previewResolutionMeters);
      int zoom = selectPreviewZoom(projection.worldScale(), previewResolutionMeters, LAND_MAX_ZOOM);
      int tilesPerAxis = 1 << zoom;
      int minX = Integer.MAX_VALUE;
      int minY = Integer.MAX_VALUE;
      int maxX = Integer.MIN_VALUE;
      int maxY = Integer.MIN_VALUE;
      double west = Math.min(minBlockX, maxBlockX);
      double east = Math.max(minBlockX, maxBlockX);
      double northOrSouthA = Math.min(minBlockZ, maxBlockZ);
      double northOrSouthB = Math.max(minBlockZ, maxBlockZ);
      double[] xs = new double[]{west, east};
      double[] zs = new double[]{northOrSouthA, northOrSouthB};

      for (double x : xs) {
         for (double z : zs) {
            double sampleX = step > 1 ? downsampleBlock(x, step) : x;
            double sampleZ = step > 1 ? downsampleBlock(z, step) : z;
            TellusElevationSource.TileKey key = tileKeyForBlock(sampleX, sampleZ, projection, zoom);
            if (key != null) {
               minX = Math.min(minX, key.x());
               minY = Math.min(minY, key.y());
               maxX = Math.max(maxX, key.x());
               maxY = Math.max(maxY, key.y());
            }
         }
      }

      if (minX == Integer.MAX_VALUE) {
         return null;
      }

      int clampedMinX = Mth.clamp(minX, 0, tilesPerAxis - 1);
      int clampedMaxX = Mth.clamp(maxX, 0, tilesPerAxis - 1);
      boolean wrapsLongitude = projection.isCentered()
         && projection.blockXToLon(west) > projection.blockXToLon(east);
      List<TellusElevationSource.TileXRange> xRanges = wrapsLongitude
         ? List.of(
            new TellusElevationSource.TileXRange(clampedMaxX, tilesPerAxis - 1),
            new TellusElevationSource.TileXRange(0, clampedMinX)
         )
         : List.of(new TellusElevationSource.TileXRange(clampedMinX, clampedMaxX));
      return new TellusElevationSource.TerrainTileBounds(
         zoom, xRanges, Mth.clamp(minY, 0, tilesPerAxis - 1), Mth.clamp(maxY, 0, tilesPerAxis - 1)
      );
   }

   private static List<TellusElevationSource.AreaSample> areaSamples(
      double minBlockX, double minBlockZ, double maxBlockX, double maxBlockZ
   ) {
      int samplesPerAxis = Math.max(1, (int)Math.ceil(Math.sqrt(AREA_PREFETCH_SAMPLE_POINTS)));
      double minX = Math.min(minBlockX, maxBlockX);
      double maxX = Math.max(minBlockX, maxBlockX);
      double minZ = Math.min(minBlockZ, maxBlockZ);
      double maxZ = Math.max(minBlockZ, maxBlockZ);
      List<TellusElevationSource.AreaSample> samples = new ArrayList<>(samplesPerAxis * samplesPerAxis);
      if (samplesPerAxis == 1) {
         samples.add(new TellusElevationSource.AreaSample((minX + maxX) * 0.5, (minZ + maxZ) * 0.5));
         return samples;
      }

      for (int z = 0; z < samplesPerAxis; z++) {
         double tz = (double)z / (samplesPerAxis - 1);
         double blockZ = Mth.lerp(tz, minZ, maxZ);
         for (int x = 0; x < samplesPerAxis; x++) {
            double tx = (double)x / (samplesPerAxis - 1);
            double blockX = Mth.lerp(tx, minX, maxX);
            samples.add(new TellusElevationSource.AreaSample(blockX, blockZ));
         }
      }

      return samples;
   }

   private void prefetchTilesFromProviders(
      double blockX,
      double blockZ,
      WorldProjection projection,
      int radius,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      this.prefetchTerrainTiles(blockX, blockZ, projection, radius, previewResolutionMeters);
   }

   private void prefetchTerrainTiles(double blockX, double blockZ, WorldProjection projection, int radius, double previewResolutionMeters) {
      int step = downsampleStep(projection.worldScale(), RESOLUTION_METERS, previewResolutionMeters);
      if (step > 1) {
         blockX = downsampleBlock(blockX, step);
         blockZ = downsampleBlock(blockZ, step);
      }

      int zoom = selectPreviewZoom(projection.worldScale(), previewResolutionMeters, LAND_MAX_ZOOM);
      this.prefetchTerrainTilesAtZoom(blockX, blockZ, projection, radius, zoom);
      if (zoom > MAPTERHORN_GLOBAL_FALLBACK_ZOOM) {
         this.prefetchTerrainTilesAtZoom(blockX, blockZ, projection, radius, MAPTERHORN_GLOBAL_FALLBACK_ZOOM);
      }
   }

   private void addTerrainTiles(
      LinkedHashSet<TellusElevationSource.RawTileRequest> plan,
      double blockX,
      double blockZ,
      WorldProjection projection,
      int radius,
      double previewResolutionMeters
   ) {
      int step = downsampleStep(projection.worldScale(), RESOLUTION_METERS, previewResolutionMeters);
      if (step > 1) {
         blockX = downsampleBlock(blockX, step);
         blockZ = downsampleBlock(blockZ, step);
      }

      int zoom = selectPreviewZoom(projection.worldScale(), previewResolutionMeters, LAND_MAX_ZOOM);
      addTilesAround(plan, blockX, blockZ, projection, radius, zoom, false);
      if (zoom > MAPTERHORN_GLOBAL_FALLBACK_ZOOM) {
         addTilesAround(plan, blockX, blockZ, projection, radius, MAPTERHORN_GLOBAL_FALLBACK_ZOOM, false);
      }
   }

   private void addOpenWatersTiles(
      LinkedHashSet<TellusElevationSource.RawTileRequest> plan,
      double blockX,
      double blockZ,
      WorldProjection projection,
      int radius,
      double previewResolutionMeters
   ) {
      int zoom = selectPreviewZoom(projection.worldScale(), previewResolutionMeters, OCEAN_MAX_ZOOM);
      addTilesAround(plan, blockX, blockZ, projection, radius, zoom, true);
      if (zoom > OPENWATERS_GLOBAL_FALLBACK_ZOOM) {
         addTilesAround(plan, blockX, blockZ, projection, radius, OPENWATERS_GLOBAL_FALLBACK_ZOOM, true);
      }
   }

   private static void addTilesAround(
      LinkedHashSet<TellusElevationSource.RawTileRequest> plan,
      double blockX,
      double blockZ,
      WorldProjection projection,
      int radius,
      int zoom,
      boolean openWaters
   ) {
      TellusElevationSource.TileKey center = tileKeyForBlock(blockX, blockZ, projection, zoom);
      if (center == null) {
         return;
      }

      int tilesPerAxis = 1 << zoom;
      int clampedRadius = Math.max(0, radius);
      int minX = Math.max(0, center.x() - clampedRadius);
      int maxX = Math.min(tilesPerAxis - 1, center.x() + clampedRadius);
      int minY = Math.max(0, center.y() - clampedRadius);
      int maxY = Math.min(tilesPerAxis - 1, center.y() + clampedRadius);
      for (int tileY = minY; tileY <= maxY; tileY++) {
         for (int tileX = minX; tileX <= maxX; tileX++) {
            plan.add(new TellusElevationSource.RawTileRequest(new TellusElevationSource.TileKey(zoom, tileX, tileY), openWaters));
         }
      }
   }

   private void downloadTerrainTile(TellusElevationSource.TileKey key) {
      this.downloadRawTile(key, this.cachePath(key), TellusCacheDomain.TERRAIN, MAPTERHORN_ENDPOINT, "Mapterhorn");
   }

   private void downloadOpenWatersTile(TellusElevationSource.TileKey key) {
      this.downloadRawTile(key, this.openWatersCachePath(key), TellusCacheDomain.OPENWATERS, OPENWATERS_ENDPOINT, "OpenWaters");
   }

   private void loadTileIntoCache(TellusElevationSource.RawTileRequest request) {
      TellusCacheDomain domain = request.openWaters() ? TellusCacheDomain.OPENWATERS : TellusCacheDomain.TERRAIN;
      long generation = TellusCacheRegistry.generation(domain);
      try {
         if (request.openWaters()) {
            this.oceanCache.get(request.key());
         } else {
            this.cache.get(request.key());
         }
      } catch (Exception error) {
         throw new RuntimeException("Failed to preload " + request.label() + " elevation tile", error);
      }
      if (Thread.currentThread().isInterrupted()) {
         throw new java.util.concurrent.CancellationException("Interrupted while preloading " + request.label());
      }
      if (!TellusCacheRegistry.isCurrent(domain, generation)) {
         if (request.openWaters()) {
            this.oceanCache.invalidate(request.key());
         } else {
            this.cache.invalidate(request.key());
         }
         throw new IllegalStateException("Discarded stale " + request.label() + " elevation preload");
      }
   }

   private void downloadRawTile(
      TellusElevationSource.TileKey key, Path cachePath, TellusCacheDomain domain, String endpoint, String providerName
   ) {
      if (Files.isRegularFile(cachePath)) {
         return;
      }

      long generation = TellusCacheRegistry.generation(domain);
      try {
         byte[] data = this.downloadTile(key, endpoint);
         if (data != null && !this.cacheTile(cachePath, data, domain, generation)) {
            throw new IOException("Discarded stale " + providerName + " cache write for " + key);
         }
      } catch (IOException error) {
         throw new RuntimeException("Failed to download " + providerName + " elevation tile " + key, error);
      }
   }

   private void prefetchTerrainTilesAtZoom(double blockX, double blockZ, WorldProjection projection, int radius, int zoom) {
      TellusElevationSource.TileKey center = tileKeyForBlock(blockX, blockZ, projection, zoom);
      if (center == null) {
         return;
      }

      this.prefetchTile(center);
      int tilesPerAxis = 1 << zoom;
      int clampedRadius = Math.max(0, radius);
      int minX = Math.max(0, center.x() - clampedRadius);
      int maxX = Math.min(tilesPerAxis - 1, center.x() + clampedRadius);
      int minY = Math.max(0, center.y() - clampedRadius);
      int maxY = Math.min(tilesPerAxis - 1, center.y() + clampedRadius);

      for (int tileY = minY; tileY <= maxY; tileY++) {
         for (int tileX = minX; tileX <= maxX; tileX++) {
            if (tileX != center.x() || tileY != center.y()) {
               this.prefetchTile(new TellusElevationSource.TileKey(zoom, tileX, tileY));
            }
         }
      }
   }

   private void prefetchOpenWatersTiles(
      double blockX, double blockZ, WorldProjection projection, int radius, double previewResolutionMeters
   ) {
      int zoom = selectPreviewZoom(projection.worldScale(), previewResolutionMeters, OCEAN_MAX_ZOOM);
      this.prefetchOpenWatersTilesAtZoom(blockX, blockZ, projection, radius, zoom);
      if (zoom > OPENWATERS_GLOBAL_FALLBACK_ZOOM) {
         this.prefetchOpenWatersTilesAtZoom(blockX, blockZ, projection, radius, OPENWATERS_GLOBAL_FALLBACK_ZOOM);
      }
   }

   private void prefetchOpenWatersTilesAtZoom(double blockX, double blockZ, WorldProjection projection, int radius, int zoom) {
      TellusElevationSource.TileKey center = tileKeyForBlock(blockX, blockZ, projection, zoom);
      if (center == null) {
         return;
      }

      this.prefetchOpenWatersTile(center);
      int tilesPerAxis = 1 << zoom;
      int clampedRadius = Math.max(0, radius);
      int minX = Math.max(0, center.x() - clampedRadius);
      int maxX = Math.min(tilesPerAxis - 1, center.x() + clampedRadius);
      int minY = Math.max(0, center.y() - clampedRadius);
      int maxY = Math.min(tilesPerAxis - 1, center.y() + clampedRadius);

      for (int tileY = minY; tileY <= maxY; tileY++) {
         for (int tileX = minX; tileX <= maxX; tileX++) {
            if (tileX != center.x() || tileY != center.y()) {
               this.prefetchOpenWatersTile(new TellusElevationSource.TileKey(zoom, tileX, tileY));
            }
         }
      }
   }

   private void prefetchOpenWatersTilesIfLikelyOcean(
      double blockX, double blockZ, WorldProjection projection, int radius, double previewResolutionMeters
   ) {
      if (projection.worldScale() <= 0.0 || radius < 0) {
         return;
      }

      try {
         TellusLandMaskSource.LandMaskSample landMaskSample = this.landMask.sampleLandMask(blockX, blockZ, projection);
         if (landMaskSample.known() && !landMaskSample.land()) {
            double mapterhorn = this.sampleTerrainTilesMetersOrMissing(
               blockX, blockZ, projection, previewResolutionMeters
            );
            if (!shouldUseBathymetry(true, mapterhorn)) {
               return;
            }
            this.prefetchOpenWatersTiles(blockX, blockZ, projection, radius, previewResolutionMeters);
         }
      } catch (RuntimeException error) {
         Tellus.LOGGER.debug("Failed to prefetch OpenWaters ocean tiles at {},{}", blockX, blockZ, error);
      }
   }

   private void prefetchNormalizedTiles(
      double blockX,
      double blockZ,
      WorldProjection projection,
      int radius,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      double effectiveResolutionMeters = effectiveSampleResolutionMeters(projection.worldScale(), previewResolutionMeters);
      double projectedX = projection.blockXToProjectedMeters(blockX);
      double projectedZ = projection.blockZToProjectedMeters(blockZ);
      double projectedRadius = Math.max(1, radius) * TILE_SIZE * projection.worldScale();
      NORMALIZED_CACHE.prefetchRange(
         projectedX - projectedRadius,
         WorldProjection.clampProjectedZ(projectedZ - projectedRadius),
         projectedX + projectedRadius,
         WorldProjection.clampProjectedZ(projectedZ + projectedRadius),
         effectiveResolutionMeters,
         demSelection,
         false,
         this::buildNormalizedTile
      );
   }

   private TellusElevationSource.ResolvedElevationSample sampleResolvedFromNormalizedCache(
      double blockX,
      double blockZ,
      WorldProjection projection,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      double effectiveResolutionMeters = effectiveSampleResolutionMeters(projection.worldScale(), previewResolutionMeters);
      if (!projection.containsHorizontalBlock(blockX, blockZ)) {
         return resolvedMapterhorn(
            Double.NaN, false, effectiveResolutionMeters, Double.NaN
         );
      }
      NormalizedElevationTileSample sample = NORMALIZED_CACHE.sample(
         projection.blockXToProjectedMeters(blockX),
         projection.blockZToProjectedMeters(blockZ),
         effectiveResolutionMeters,
         demSelection,
         highResOcean,
         this::buildNormalizedTile
      );
      return new TellusElevationSource.ResolvedElevationSample(
         sample.elevationMeters(),
         sample.primaryProvider(),
         sample.providerMask(),
         sample.resolutionMeters(),
         sample.mapterhornAvailable(),
         sample.mapterhornElevationMeters()
      );
   }

   private NormalizedElevationTile buildNormalizedTile(NormalizedElevationTileKey key) throws IOException {
      this.prefetchNormalizedIngestSources(key);
      ShortRaster heights = ShortRaster.create(NormalizedElevationTileKey.TILE_SIZE, NormalizedElevationTileKey.TILE_SIZE);
      ShortRaster mapterhornHeights = ShortRaster.create(NormalizedElevationTileKey.TILE_SIZE, NormalizedElevationTileKey.TILE_SIZE);
      int sampleCount = NormalizedElevationTileKey.TILE_SIZE * NormalizedElevationTileKey.TILE_SIZE;
      byte[] primaryProviders = new byte[sampleCount];
      byte[] blendedFlags = new byte[TellusElevationProvenance.bitSetLength(sampleCount)];
      byte[] mapterhornAvailableFlags = new byte[TellusElevationProvenance.bitSetLength(sampleCount)];
      int providerMask = 0;
      double sampleResolutionMeters = key.sampleResolutionMeters();
      // Normalized tiles are keyed by global projected metres, so their pseudo-blocks use a global projection.
      WorldProjection tileProjection = WorldProjection.global(sampleResolutionMeters);

      for (int localZ = 0; localZ < NormalizedElevationTileKey.TILE_SIZE; localZ++) {
         for (int localX = 0; localX < NormalizedElevationTileKey.TILE_SIZE; localX++) {
            double projectedX = key.sampleProjectedX(localX);
            double projectedZ = key.sampleProjectedZ(localZ);
            // A centered world can cross the real-world antimeridian inside playable block space. Normalized
            // cache tiles beyond one Mercator edge sample the wrapped opposite edge so interpolation stays
            // continuous instead of treating the antimeridian as missing coverage.
            double pseudoBlockX = WorldProjection.wrapProjectedX(projectedX) / sampleResolutionMeters;
            double pseudoBlockZ = WorldProjection.clampProjectedZ(projectedZ) / sampleResolutionMeters;
            TellusElevationSource.ResolvedElevationSample resolved = this.sampleResolvedElevationFromProviders(
               pseudoBlockX, pseudoBlockZ, tileProjection, key.highResOcean(), key.demSelection(), sampleResolutionMeters
            );
            if (!isUsablePreviewElevation(resolved)) {
               throw new IOException("Incomplete elevation coverage while building normalized tile " + key);
            }

            int sampleIndex = localX + localZ * NormalizedElevationTileKey.TILE_SIZE;
            double elevationMeters = Double.isFinite(resolved.elevationMeters()) ? resolved.elevationMeters() : 0.0;
            int roundedElevation = Mth.clamp((int)Math.round(elevationMeters), (int)Short.MIN_VALUE, (int)Short.MAX_VALUE);
            heights.set(localX, localZ, (short)roundedElevation);
            double mapterhornElevation = Double.isFinite(resolved.mapterhornElevationMeters())
               ? resolved.mapterhornElevationMeters()
               : 0.0;
            int roundedMapterhorn = Mth.clamp((int)Math.round(mapterhornElevation), (int)Short.MIN_VALUE, (int)Short.MAX_VALUE);
            mapterhornHeights.set(localX, localZ, (short)roundedMapterhorn);
            primaryProviders[sampleIndex] = (byte)resolved.primaryProvider().ordinal();
            providerMask |= resolved.providerMask();
            if (Integer.bitCount(resolved.providerMask()) > 1) {
               blendedFlags[sampleIndex >> 3] = (byte)(blendedFlags[sampleIndex >> 3] | 1 << (sampleIndex & 7));
            }
            if (resolved.mapterhornAvailable()) {
               mapterhornAvailableFlags[sampleIndex >> 3] = (byte)(
                  mapterhornAvailableFlags[sampleIndex >> 3] | 1 << (sampleIndex & 7)
               );
            }
         }
      }

      return new NormalizedElevationTile(
         key,
         heights,
         mapterhornHeights,
         new TellusElevationProvenance(
            NormalizedElevationTileKey.TILE_SIZE,
            NormalizedElevationTileKey.TILE_SIZE,
            providerMask,
            primaryProviders,
            blendedFlags,
            mapterhornAvailableFlags
         )
      );
   }

   private void prefetchNormalizedIngestSources(NormalizedElevationTileKey key) {
      double sampleResolutionMeters = key.sampleResolutionMeters();
      double centerBlockX = WorldProjection.wrapProjectedX(
         key.sampleProjectedX(NormalizedElevationTileKey.TILE_SIZE / 2)
      ) / sampleResolutionMeters;
      double centerBlockZ = key.sampleProjectedZ(NormalizedElevationTileKey.TILE_SIZE / 2) / sampleResolutionMeters;

      try {
         this.prefetchTilesFromProviders(
            centerBlockX, centerBlockZ, WorldProjection.global(sampleResolutionMeters), 1, key.demSelection(), sampleResolutionMeters
         );
      } catch (RuntimeException error) {
         Tellus.LOGGER.debug("Failed to prefetch ingest sources for normalized elevation tile {}", key, error);
      }
   }

   private void compareNormalizedElevation(
      double blockX,
      double blockZ,
      WorldProjection projection,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters,
      double normalizedElevationMeters
   ) {
      if (!NORMALIZED_COMPARE) {
         return;
      }

      double providerElevationMeters = this.sampleElevationMetersFromProviders(
         blockX, blockZ, projection, highResOcean, demSelection, previewResolutionMeters
      );
      if (Math.abs(providerElevationMeters - normalizedElevationMeters) > 1.0) {
         this.logNormalizedMismatch(blockX, blockZ, projection, demSelection, previewResolutionMeters, normalizedElevationMeters, providerElevationMeters);
      }
   }

   private void logNormalizedMismatch(
      double blockX,
      double blockZ,
      WorldProjection projection,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters,
      double normalizedElevationMeters,
      double providerElevationMeters
   ) {
      int logged = NORMALIZED_COMPARE_LOGGED.getAndIncrement();
      if (logged < NORMALIZED_COMPARE_LOG_LIMIT) {
         Tellus.LOGGER.warn(
            "Normalized elevation mismatch at {},{} scale={} selection={} resolution={} normalized={} provider={}",
            new Object[]{
               blockX,
               blockZ,
               projection,
               demSelection.fingerprint(),
               previewResolutionMeters,
               normalizedElevationMeters,
               providerElevationMeters
            }
         );
      }
   }

   private double sampleTerrariumMetersLocalOnly(double blockX, double blockZ, WorldProjection projection, boolean highResOcean, double previewResolutionMeters) {
      return this.sampleTerrariumMetersLocalOnly(
         blockX, blockZ, projection, highResOcean, EarthGeneratorSettings.DEFAULT.demSelection(), previewResolutionMeters
      );
   }

   private double sampleTerrariumMetersLocalOnly(
      double blockX,
      double blockZ,
      WorldProjection projection,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      return this.sampleResolvedElevationFromProvidersLocalOnly(
         blockX, blockZ, projection, highResOcean, demSelection, previewResolutionMeters
      ).elevationMeters();
   }

   private double sampleTerrainTilesMetersLocalOnlyOrMissing(
      double blockX, double blockZ, WorldProjection projection, double previewResolutionMeters
   ) {
      int step = downsampleStep(projection.worldScale(), RESOLUTION_METERS, previewResolutionMeters);
      if (step > 1) {
         blockX = downsampleBlock(blockX, step);
         blockZ = downsampleBlock(blockZ, step);
      }

      int zoom = selectPreviewZoom(projection.worldScale(), previewResolutionMeters, LAND_MAX_ZOOM);
      return this.sampleAtBestAvailableZoomLocalOnly(blockX, blockZ, projection, zoom);
   }

   private double sampleTerrainTilesMetersMemoryOnly(double blockX, double blockZ, WorldProjection projection, double previewResolutionMeters) {
      int step = downsampleStep(projection.worldScale(), RESOLUTION_METERS, previewResolutionMeters);
      if (step > 1) {
         blockX = downsampleBlock(blockX, step);
         blockZ = downsampleBlock(blockZ, step);
      }

      int zoom = selectPreviewZoom(projection.worldScale(), previewResolutionMeters, LAND_MAX_ZOOM);
      double sample = this.sampleAtBestAvailableZoomMemoryOnly(blockX, blockZ, projection, zoom);
      if (!Double.isNaN(sample)) {
         return sample;
      } else {
         return Double.NaN;
      }
   }

   private double terrainTilesResolutionMeters(
      double blockX, double blockZ, WorldProjection projection, double previewResolutionMeters
   ) {
      return resolvedMapterhornSourceResolution(
         this.mapterhornResolutionSource.lookupResolutionMeters(blockX, blockZ, projection, previewResolutionMeters)
      );
   }

   private static double resolvedMapterhornSourceResolution(double resolutionMeters) {
      return Double.isFinite(resolutionMeters) && resolutionMeters > 0.0
         ? resolutionMeters
         : TellusElevationSource.DemUsage.TERRAIN_TILES.nominalResolutionMeters();
   }

   private double sampleTerrainTilesMetersOrMissing(
      double blockX, double blockZ, WorldProjection projection, double previewResolutionMeters
   ) {
      int step = downsampleStep(projection.worldScale(), RESOLUTION_METERS, previewResolutionMeters);
      if (step > 1) {
         blockX = downsampleBlock(blockX, step);
         blockZ = downsampleBlock(blockZ, step);
      }

      int zoom = selectPreviewZoom(projection.worldScale(), previewResolutionMeters, LAND_MAX_ZOOM);
      return this.sampleAtBestAvailableZoom(blockX, blockZ, projection, zoom);
   }

   private TellusElevationSource.OceanElevationSample sampleOceanElevation(
      double blockX,
      double blockZ,
      WorldProjection projection,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      if (projection.worldScale() <= 0.0) {
         return TellusElevationSource.OceanElevationSample.none();
      }

      double openWaters = this.sampleOpenWatersBathymetry(blockX, blockZ, projection, previewResolutionMeters);
      if (Double.isFinite(openWaters)) {
         return new TellusElevationSource.OceanElevationSample(openWaters, TellusElevationSource.DemUsage.OPENWATERS);
      }

      double terrainTiles = this.sampleTerrainTilesOceanFallback(blockX, blockZ, projection, previewResolutionMeters);
      return Double.isFinite(terrainTiles)
         ? new TellusElevationSource.OceanElevationSample(terrainTiles, TellusElevationSource.DemUsage.TERRAIN_TILES)
         : TellusElevationSource.OceanElevationSample.none();
   }

   private TellusElevationSource.OceanElevationSample sampleOceanElevationLocalOnly(
      double blockX, double blockZ, WorldProjection projection, double previewResolutionMeters
   ) {
      if (projection.worldScale() <= 0.0) {
         return TellusElevationSource.OceanElevationSample.none();
      }

      double openWaters = this.sampleOpenWatersBathymetryLocalOnly(blockX, blockZ, projection, previewResolutionMeters);
      if (Double.isFinite(openWaters)) {
         return new TellusElevationSource.OceanElevationSample(openWaters, TellusElevationSource.DemUsage.OPENWATERS);
      }

      double terrainTiles = this.sampleTerrainTilesOceanFallbackLocalOnly(blockX, blockZ, projection, previewResolutionMeters);
      return Double.isFinite(terrainTiles)
         ? new TellusElevationSource.OceanElevationSample(terrainTiles, TellusElevationSource.DemUsage.TERRAIN_TILES)
         : TellusElevationSource.OceanElevationSample.none();
   }

   private TellusElevationSource.OceanElevationSample sampleOceanElevationMemoryOnly(
      double blockX, double blockZ, WorldProjection projection, double previewResolutionMeters
   ) {
      if (projection.worldScale() <= 0.0) {
         return TellusElevationSource.OceanElevationSample.none();
      }

      double openWaters = this.sampleOpenWatersBathymetryMemoryOnly(blockX, blockZ, projection, previewResolutionMeters);
      if (Double.isFinite(openWaters)) {
         return new TellusElevationSource.OceanElevationSample(openWaters, TellusElevationSource.DemUsage.OPENWATERS);
      }

      double terrainTiles = this.sampleTerrainTilesOceanFallbackMemoryOnly(blockX, blockZ, projection, previewResolutionMeters);
      return Double.isFinite(terrainTiles)
         ? new TellusElevationSource.OceanElevationSample(terrainTiles, TellusElevationSource.DemUsage.TERRAIN_TILES)
         : TellusElevationSource.OceanElevationSample.none();
   }

   private double sampleOpenWatersBathymetry(double blockX, double blockZ, WorldProjection projection, double previewResolutionMeters) {
      int zoom = selectPreviewZoom(projection.worldScale(), previewResolutionMeters, OCEAN_MAX_ZOOM);
      return this.sampleOpenWatersAtBestAvailableZoom(blockX, blockZ, projection, zoom);
   }

   private double sampleOpenWatersBathymetryLocalOnly(
      double blockX, double blockZ, WorldProjection projection, double previewResolutionMeters
   ) {
      int zoom = selectPreviewZoom(projection.worldScale(), previewResolutionMeters, OCEAN_MAX_ZOOM);
      return this.sampleOpenWatersAtBestAvailableZoomLocalOnly(blockX, blockZ, projection, zoom);
   }

   private double sampleOpenWatersBathymetryMemoryOnly(
      double blockX, double blockZ, WorldProjection projection, double previewResolutionMeters
   ) {
      int zoom = selectPreviewZoom(projection.worldScale(), previewResolutionMeters, OCEAN_MAX_ZOOM);
      return this.sampleOpenWatersAtBestAvailableZoomMemoryOnly(blockX, blockZ, projection, zoom);
   }

   private double sampleTerrainTilesOceanFallback(double blockX, double blockZ, WorldProjection projection, double previewResolutionMeters) {
      int step = downsampleStep(projection.worldScale(), RESOLUTION_METERS, previewResolutionMeters);
      if (step > 1) {
         blockX = downsampleBlock(blockX, step);
         blockZ = downsampleBlock(blockZ, step);
      }

      int zoom = Math.min(
         MAPTERHORN_OCEAN_FALLBACK_ZOOM,
         selectPreviewZoom(projection.worldScale(), previewResolutionMeters, LAND_MAX_ZOOM)
      );
      return this.sampleAtZoom(blockX, blockZ, projection, zoom);
   }

   private double sampleTerrainTilesOceanFallbackLocalOnly(
      double blockX, double blockZ, WorldProjection projection, double previewResolutionMeters
   ) {
      int step = downsampleStep(projection.worldScale(), RESOLUTION_METERS, previewResolutionMeters);
      if (step > 1) {
         blockX = downsampleBlock(blockX, step);
         blockZ = downsampleBlock(blockZ, step);
      }

      int zoom = Math.min(
         MAPTERHORN_OCEAN_FALLBACK_ZOOM,
         selectPreviewZoom(projection.worldScale(), previewResolutionMeters, LAND_MAX_ZOOM)
      );
      return this.sampleAtZoomLocalOnly(blockX, blockZ, projection, zoom);
   }

   private double sampleTerrainTilesOceanFallbackMemoryOnly(
      double blockX, double blockZ, WorldProjection projection, double previewResolutionMeters
   ) {
      int step = downsampleStep(projection.worldScale(), RESOLUTION_METERS, previewResolutionMeters);
      if (step > 1) {
         blockX = downsampleBlock(blockX, step);
         blockZ = downsampleBlock(blockZ, step);
      }

      int zoom = Math.min(
         MAPTERHORN_OCEAN_FALLBACK_ZOOM,
         selectPreviewZoom(projection.worldScale(), previewResolutionMeters, LAND_MAX_ZOOM)
      );
      return this.sampleAtZoomMemoryOnly(blockX, blockZ, projection, zoom);
   }

   private double sampleAtZoom(double blockX, double blockZ, WorldProjection projection, int zoom) {
      double lon = projection.blockXToLon(blockX);
      double lat = projection.blockZToLat(blockZ);
      if (!(lat < MIN_LAT) && !(lat > MAX_LAT) && !(lon < MIN_LON) && !(lon > MAX_LON)) {
         double latRad = Math.toRadians(lat);
         double n = Math.pow(2.0, zoom);
         double x = (lon + 180.0) / 360.0 * n;
         double y = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n;
         if (!(x < 0.0) && !(y < 0.0) && !(x >= n) && !(y >= n)) {
            int tileX = Mth.floor(x);
            int tileY = Mth.floor(y);
            ShortRaster raster = this.getTile(new TellusElevationSource.TileKey(zoom, tileX, tileY));
            if (raster == null) {
               return Double.NaN;
            } else {
               double globalX = x * TILE_SIZE;
               double globalY = y * TILE_SIZE;
               return this.sampleBilinearAcrossTiles(zoom, globalX, globalY, tileX, tileY, raster);
            }
         } else {
            return Double.NaN;
         }
      } else {
         return Double.NaN;
      }
   }

   private double sampleAtZoomLocalOnly(double blockX, double blockZ, WorldProjection projection, int zoom) {
      double lon = projection.blockXToLon(blockX);
      double lat = projection.blockZToLat(blockZ);
      if (!(lat < MIN_LAT) && !(lat > MAX_LAT) && !(lon < MIN_LON) && !(lon > MAX_LON)) {
         double latRad = Math.toRadians(lat);
         double n = Math.pow(2.0, zoom);
         double x = (lon + 180.0) / 360.0 * n;
         double y = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n;
         if (!(x < 0.0) && !(y < 0.0) && !(x >= n) && !(y >= n)) {
            int tileX = Mth.floor(x);
            int tileY = Mth.floor(y);
            ShortRaster raster = this.getTileLocalOnly(new TellusElevationSource.TileKey(zoom, tileX, tileY));
            if (raster == null) {
               return Double.NaN;
            } else {
               double globalX = x * TILE_SIZE;
               double globalY = y * TILE_SIZE;
               return this.sampleBilinearAcrossTilesLocalOnly(zoom, globalX, globalY, tileX, tileY, raster);
            }
         } else {
            return Double.NaN;
         }
      } else {
         return Double.NaN;
      }
   }

   private double sampleAtZoomMemoryOnly(double blockX, double blockZ, WorldProjection projection, int zoom) {
      double lon = projection.blockXToLon(blockX);
      double lat = projection.blockZToLat(blockZ);
      if (!(lat < MIN_LAT) && !(lat > MAX_LAT) && !(lon < MIN_LON) && !(lon > MAX_LON)) {
         double latRad = Math.toRadians(lat);
         double n = Math.pow(2.0, zoom);
         double x = (lon + 180.0) / 360.0 * n;
         double y = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n;
         if (!(x < 0.0) && !(y < 0.0) && !(x >= n) && !(y >= n)) {
            int tileX = Mth.floor(x);
            int tileY = Mth.floor(y);
            ShortRaster raster = this.getTileMemoryOnly(new TellusElevationSource.TileKey(zoom, tileX, tileY));
            if (raster == null) {
               return Double.NaN;
            } else {
               double globalX = x * TILE_SIZE;
               double globalY = y * TILE_SIZE;
               return this.sampleBilinearAcrossTilesMemoryOnly(zoom, globalX, globalY, tileX, tileY, raster);
            }
         } else {
            return Double.NaN;
         }
      } else {
         return Double.NaN;
      }
   }

   private double sampleOpenWatersAtZoom(double blockX, double blockZ, WorldProjection projection, int zoom) {
      double lon = projection.blockXToLon(blockX);
      double lat = projection.blockZToLat(blockZ);
      if (!(lat < MIN_LAT) && !(lat > MAX_LAT) && !(lon < MIN_LON) && !(lon > MAX_LON)) {
         double latRad = Math.toRadians(lat);
         double n = Math.pow(2.0, zoom);
         double x = (lon + 180.0) / 360.0 * n;
         double y = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n;
         if (!(x < 0.0) && !(y < 0.0) && !(x >= n) && !(y >= n)) {
            int tileX = Mth.floor(x);
            int tileY = Mth.floor(y);
            ShortRaster raster = this.getOpenWatersTile(new TellusElevationSource.TileKey(zoom, tileX, tileY));
            if (raster == null) {
               return Double.NaN;
            } else {
               double globalX = x * TILE_SIZE;
               double globalY = y * TILE_SIZE;
               return this.sampleOpenWatersBilinearAcrossTiles(zoom, globalX, globalY, tileX, tileY, raster);
            }
         } else {
            return Double.NaN;
         }
      } else {
         return Double.NaN;
      }
   }

   private double sampleOpenWatersAtZoomLocalOnly(double blockX, double blockZ, WorldProjection projection, int zoom) {
      double lon = projection.blockXToLon(blockX);
      double lat = projection.blockZToLat(blockZ);
      if (!(lat < MIN_LAT) && !(lat > MAX_LAT) && !(lon < MIN_LON) && !(lon > MAX_LON)) {
         double latRad = Math.toRadians(lat);
         double n = Math.pow(2.0, zoom);
         double x = (lon + 180.0) / 360.0 * n;
         double y = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n;
         if (!(x < 0.0) && !(y < 0.0) && !(x >= n) && !(y >= n)) {
            int tileX = Mth.floor(x);
            int tileY = Mth.floor(y);
            ShortRaster raster = this.getOpenWatersTileLocalOnly(new TellusElevationSource.TileKey(zoom, tileX, tileY));
            if (raster == null) {
               return Double.NaN;
            } else {
               double globalX = x * TILE_SIZE;
               double globalY = y * TILE_SIZE;
               return this.sampleOpenWatersBilinearAcrossTilesLocalOnly(zoom, globalX, globalY, tileX, tileY, raster);
            }
         } else {
            return Double.NaN;
         }
      } else {
         return Double.NaN;
      }
   }

   private double sampleOpenWatersAtZoomMemoryOnly(double blockX, double blockZ, WorldProjection projection, int zoom) {
      double lon = projection.blockXToLon(blockX);
      double lat = projection.blockZToLat(blockZ);
      if (!(lat < MIN_LAT) && !(lat > MAX_LAT) && !(lon < MIN_LON) && !(lon > MAX_LON)) {
         double latRad = Math.toRadians(lat);
         double n = Math.pow(2.0, zoom);
         double x = (lon + 180.0) / 360.0 * n;
         double y = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n;
         if (!(x < 0.0) && !(y < 0.0) && !(x >= n) && !(y >= n)) {
            int tileX = Mth.floor(x);
            int tileY = Mth.floor(y);
            ShortRaster raster = this.getOpenWatersTileMemoryOnly(new TellusElevationSource.TileKey(zoom, tileX, tileY));
            if (raster == null) {
               return Double.NaN;
            } else {
               double globalX = x * TILE_SIZE;
               double globalY = y * TILE_SIZE;
               return this.sampleOpenWatersBilinearAcrossTilesMemoryOnly(zoom, globalX, globalY, tileX, tileY, raster);
            }
         } else {
            return Double.NaN;
         }
      } else {
         return Double.NaN;
      }
   }

   private double sampleAtBestAvailableZoom(double blockX, double blockZ, WorldProjection projection, int zoom) {
      int minFallbackZoom = mapterhornMinimumFallbackZoom(zoom);
      for (int currentZoom = zoom; currentZoom >= minFallbackZoom; currentZoom--) {
         double sample = this.sampleAtZoom(blockX, blockZ, projection, currentZoom);
         if (!Double.isNaN(sample)) {
            return sample;
         }
      }

      return Double.NaN;
   }

   private double sampleOpenWatersAtBestAvailableZoom(double blockX, double blockZ, WorldProjection projection, int zoom) {
      int minFallbackZoom = zoom > OPENWATERS_GLOBAL_FALLBACK_ZOOM ? OPENWATERS_GLOBAL_FALLBACK_ZOOM : MIN_ZOOM;
      for (int currentZoom = zoom; currentZoom >= minFallbackZoom; currentZoom--) {
         double sample = this.sampleOpenWatersAtZoom(blockX, blockZ, projection, currentZoom);
         if (!Double.isNaN(sample)) {
            return sample;
         }
      }

      return Double.NaN;
   }

   private double sampleOpenWatersAtBestAvailableZoomLocalOnly(double blockX, double blockZ, WorldProjection projection, int zoom) {
      int minFallbackZoom = zoom > OPENWATERS_GLOBAL_FALLBACK_ZOOM ? OPENWATERS_GLOBAL_FALLBACK_ZOOM : MIN_ZOOM;
      for (int currentZoom = zoom; currentZoom >= minFallbackZoom; currentZoom--) {
         double sample = this.sampleOpenWatersAtZoomLocalOnly(blockX, blockZ, projection, currentZoom);
         if (!Double.isNaN(sample)) {
            return sample;
         }
      }

      return Double.NaN;
   }

   private double sampleOpenWatersAtBestAvailableZoomMemoryOnly(double blockX, double blockZ, WorldProjection projection, int zoom) {
      int minFallbackZoom = zoom > OPENWATERS_GLOBAL_FALLBACK_ZOOM ? OPENWATERS_GLOBAL_FALLBACK_ZOOM : MIN_ZOOM;
      for (int currentZoom = zoom; currentZoom >= minFallbackZoom; currentZoom--) {
         double sample = this.sampleOpenWatersAtZoomMemoryOnly(blockX, blockZ, projection, currentZoom);
         if (!Double.isNaN(sample)) {
            return sample;
         }
      }

      return Double.NaN;
   }

   private double sampleAtBestAvailableZoomLocalOnly(double blockX, double blockZ, WorldProjection projection, int zoom) {
      int minFallbackZoom = mapterhornMinimumFallbackZoom(zoom);
      for (int currentZoom = zoom; currentZoom >= minFallbackZoom; currentZoom--) {
         double sample = this.sampleAtZoomLocalOnly(blockX, blockZ, projection, currentZoom);
         if (!Double.isNaN(sample)) {
            return sample;
         }
      }

      return Double.NaN;
   }

   private double sampleAtBestAvailableZoomMemoryOnly(double blockX, double blockZ, WorldProjection projection, int zoom) {
      int minFallbackZoom = mapterhornMinimumFallbackZoom(zoom);
      for (int currentZoom = zoom; currentZoom >= minFallbackZoom; currentZoom--) {
         double sample = this.sampleAtZoomMemoryOnly(blockX, blockZ, projection, currentZoom);
         if (!Double.isNaN(sample)) {
            return sample;
         }
      }

      return Double.NaN;
   }

   private static int downsampleStep(double worldScale, double resolutionMeters, double previewResolutionMeters) {
      if (!(worldScale > 0.0)) {
         return 1;
      } else if (!(effectiveSampleResolutionMeters(worldScale, previewResolutionMeters) >= resolutionMeters)) {
         return 1;
      } else {
         return Math.max(1, Mth.floor(resolutionMeters / worldScale));
      }
   }

   static int mapterhornMinimumFallbackZoom(int requestedZoom) {
      return requestedZoom > MAPTERHORN_GLOBAL_FALLBACK_ZOOM
         ? MAPTERHORN_GLOBAL_FALLBACK_ZOOM
         : Math.max(MIN_ZOOM, requestedZoom);
   }

   static boolean mapterhornMissingTileRepresentsZero(int zoom) {
      return zoom >= MIN_ZOOM && zoom <= MAPTERHORN_GLOBAL_FALLBACK_ZOOM;
   }

   private static double downsampleBlock(double blockCoord, int step) {
      if (step <= 1) {
         return blockCoord;
      } else {
         int block = Mth.floor(blockCoord);
         int snapped = Math.floorDiv(block, step) * step;
         return snapped + step * 0.5;
      }
   }

   private static TellusElevationSource.TileKey tileKeyForBlock(double blockX, double blockZ, WorldProjection projection, int zoom) {
      double lon = projection.blockXToLon(blockX);
      double lat = projection.blockZToLat(blockZ);
      if (!(lat < MIN_LAT) && !(lat > MAX_LAT) && !(lon < MIN_LON) && !(lon > MAX_LON)) {
         double latRad = Math.toRadians(lat);
         double n = Math.pow(2.0, zoom);
         double x = (lon + 180.0) / 360.0 * n;
         double y = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n;
         if (!(x < 0.0) && !(y < 0.0) && !(x >= n) && !(y >= n)) {
            int tileX = Mth.floor(x);
            int tileY = Mth.floor(y);
            return new TellusElevationSource.TileKey(zoom, tileX, tileY);
         } else {
            return null;
         }
      } else {
         return null;
      }
   }

   private static double effectiveSampleResolutionMeters(double worldScale, double previewResolutionMeters) {
      return Double.isFinite(previewResolutionMeters) && previewResolutionMeters > 0.0 ? Math.max(worldScale, previewResolutionMeters) : worldScale;
   }

   private void prefetchTile( TellusElevationSource.TileKey key) {
      if (this.cache.getIfPresent(key) == null) {
         try {
            this.cache.get(key);
         } catch (Exception var3) {
            Tellus.LOGGER.debug("Failed to prefetch elevation tile {}", key, var3);
         }
      }
   }

   /**
    * Clears negative in-memory results so a coherent preview pass can retry transient download failures.
    * Successfully decoded tiles remain cached.
    */
   public void retryMissingTiles() {
      this.cache.asMap().entrySet().removeIf(entry -> entry.getValue() == MISSING_RASTER);
      this.oceanCache.asMap().entrySet().removeIf(entry -> entry.getValue() == MISSING_RASTER);
      this.mapterhornResolutionSource.retryMissingTiles();
   }

   private void prefetchOpenWatersTile(TellusElevationSource.TileKey key) {
      if (this.oceanCache.getIfPresent(key) == null) {
         try {
            this.oceanCache.get(key);
         } catch (Exception error) {
            Tellus.LOGGER.debug("Failed to prefetch OpenWaters bathymetry tile {}", key, error);
         }
      }
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

   private static boolean booleanProperty(String key, boolean defaultValue) {
      String value = System.getProperty(key);
      return value == null ? defaultValue : Boolean.parseBoolean(value);
   }

   private ShortRaster getTile( TellusElevationSource.TileKey key) {
      try {
         ShortRaster raster = (ShortRaster)this.cache.get(key);
         return raster == MISSING_RASTER ? null : raster;
      } catch (Exception var3) {
         Tellus.LOGGER.warn("Failed to load elevation tile {}", key, var3);
         return null;
      }
   }

   private ShortRaster getOpenWatersTile(TellusElevationSource.TileKey key) {
      try {
         ShortRaster raster = (ShortRaster)this.oceanCache.get(key);
         return raster == MISSING_RASTER ? null : raster;
      } catch (Exception error) {
         Tellus.LOGGER.warn("Failed to load OpenWaters bathymetry tile {}", key, error);
         return null;
      }
   }

   private ShortRaster getTileLocalOnly(TellusElevationSource.TileKey key) {
      ShortRaster cached = (ShortRaster)this.cache.getIfPresent(key);
      if (cached != null) {
         return cached == MISSING_RASTER ? null : cached;
      } else {
         Path cachePath = this.cachePath(key);
         if (!Files.exists(cachePath)) {
            return null;
         } else {
            try {
               ShortRaster raster = readCachedTerrainRaster(cachePath);
               this.cache.put(key, raster);
               return raster;
            } catch (IOException error) {
               this.handleInvalidTile(cachePath, key, error);
               return null;
            }
         }
      }
   }

   private ShortRaster getOpenWatersTileLocalOnly(TellusElevationSource.TileKey key) {
      ShortRaster cached = (ShortRaster)this.oceanCache.getIfPresent(key);
      if (cached != null) {
         return cached == MISSING_RASTER ? null : cached;
      } else {
         Path cachePath = this.openWatersCachePath(key);
         if (!Files.exists(cachePath)) {
            return null;
         } else {
            try {
               ShortRaster raster = readCachedTerrainRaster(cachePath);
               this.oceanCache.put(key, raster);
               return raster;
            } catch (IOException error) {
               this.handleInvalidTile(cachePath, key, error);
               return null;
            }
         }
      }
   }

   private ShortRaster getTileMemoryOnly(TellusElevationSource.TileKey key) {
      ShortRaster cached = (ShortRaster)this.cache.getIfPresent(key);
      return cached == null || cached == MISSING_RASTER ? null : cached;
   }

   private ShortRaster getOpenWatersTileMemoryOnly(TellusElevationSource.TileKey key) {
      ShortRaster cached = (ShortRaster)this.oceanCache.getIfPresent(key);
      return cached == null || cached == MISSING_RASTER ? null : cached;
   }

   private ShortRaster loadTile( TellusElevationSource.TileKey key) {
      return this.loadTile(key, this.cachePath(key), TellusCacheDomain.TERRAIN, MAPTERHORN_ENDPOINT, "Mapterhorn");
   }

   private ShortRaster loadOpenWatersTile(TellusElevationSource.TileKey key) {
      return this.loadTile(key, this.openWatersCachePath(key), TellusCacheDomain.OPENWATERS, OPENWATERS_ENDPOINT, "OpenWaters");
   }

   private ShortRaster loadTile(
      TellusElevationSource.TileKey key, Path cachePath, TellusCacheDomain cacheDomain, String endpoint, String providerName
   ) {
      if (Files.exists(cachePath)) {
         try {
            return readCachedTerrainRaster(cachePath);
         } catch (IOException var13) {
            this.handleInvalidTile(cachePath, key, var13);
         }
      }

      long generation = TellusCacheRegistry.generation(cacheDomain);
      byte[] data;
      try {
         data = this.downloadTile(key, endpoint);
      } catch (IOException var10) {
         Tellus.LOGGER.debug("Failed to download {} elevation tile {}", providerName, key, var10);
         return MISSING_RASTER;
      }

      if (data == null) {
         // The global Mapterhorn pyramid omits empty ocean tiles. Copernicus GLO-30
         // defines those gaps as zero elevation, so walking to coarser parents only
         // adds serialized HTTP misses and can blend distant land into ocean samples.
         return cacheDomain == TellusCacheDomain.TERRAIN && mapterhornMissingTileRepresentsZero(key.zoom())
            ? ZERO_RASTER
            : MISSING_RASTER;
      } else {
         if (!this.cacheTile(cachePath, data, cacheDomain, generation)) {
            throw new IllegalStateException("Discarded stale " + providerName + " elevation cache write for " + key);
         }

         try {
            ShortRaster var5;
            try (InputStream input = new ByteArrayInputStream(data)) {
               var5 = readTerrainRaster(input);
            }

            return var5;
         } catch (IOException var9) {
            this.handleInvalidTile(cachePath, key, var9);
            return MISSING_RASTER;
         }
      }
   }

   private byte[] downloadTile(TellusElevationSource.TileKey key, String endpoint) throws IOException {
      IOException lastFailure = null;
      for (int attempt = 1; attempt <= TILE_DOWNLOAD_ATTEMPTS; attempt++) {
         try {
            return this.downloadTileOnce(key, endpoint);
         } catch (IOException error) {
            lastFailure = error;
            if (attempt < TILE_DOWNLOAD_ATTEMPTS) {
               Tellus.LOGGER.debug(
                  "Elevation tile {}/{}/{} download attempt {}/{} failed; retrying",
                  key.zoom(),
                  key.x(),
                  key.y(),
                  attempt,
                  TILE_DOWNLOAD_ATTEMPTS,
                  error
               );
               sleepBeforeTileRetry(attempt);
            }
         }
      }
      throw Objects.requireNonNull(lastFailure, "elevationTileDownloadFailure");
   }

   private byte[] downloadTileOnce(TellusElevationSource.TileKey key, String endpoint) throws IOException {
      URI uri = URI.create(String.format("%s/%d/%d/%d.webp", endpoint, key.zoom(), key.x(), key.y()));
      HttpURLConnection connection = (HttpURLConnection)uri.toURL().openConnection();
      try {
         connection.setConnectTimeout(TILE_CONNECT_TIMEOUT_MS);
         connection.setReadTimeout(TILE_READ_TIMEOUT_MS);
         connection.setRequestProperty("User-Agent", "Tellus/1.0 (Minecraft Mod)");
         int status = connection.getResponseCode();
         if (status == 404) {
            return null;
         }
         if (status < 200 || status >= 300) {
            throw new IOException("Elevation tile request returned HTTP " + status + " for " + key);
         }

         long contentLength = connection.getContentLengthLong();
         if (contentLength > MAX_TILE_BYTES) {
            throw new IOException("Elevation tile response exceeds the safety limit for " + key);
         }

         DownloadProgressReporter.requestStarted(contentLength);

         try (InputStream input = Objects.requireNonNull(connection.getInputStream(), "elevationTileResponse")) {
            return DownloadProgressReporter.readAllBytesWithProgress(input, MAX_TILE_BYTES);
         } finally {
            DownloadProgressReporter.requestFinished();
         }
      } finally {
         connection.disconnect();
      }
   }

   private static void sleepBeforeTileRetry(int attempt) throws IOException {
      try {
         Thread.sleep((long)TILE_RETRY_BACKOFF_MS * attempt);
      } catch (InterruptedException error) {
         Thread.currentThread().interrupt();
         throw new IOException("Elevation tile retry interrupted", error);
      }
   }

   private boolean cacheTile(Path cachePath, byte[] data, TellusCacheDomain domain, long generation) {
      try {
         return TellusCacheFiles.writeBytesIfCurrent(domain, generation, cachePath, data);
      } catch (IOException var4) {
         Tellus.LOGGER.warn("Failed to cache elevation tile {}", cachePath, var4);
         return false;
      }
   }

   private Path cachePath(TellusElevationSource.TileKey key) {
      return this.cacheRoot.resolve(key.zoom() + "/" + key.x() + "/" + key.y() + ".webp");
   }

   private Path openWatersCachePath(TellusElevationSource.TileKey key) {
      return this.oceanCacheRoot.resolve(key.zoom() + "/" + key.x() + "/" + key.y() + ".webp");
   }

   private void handleInvalidTile(Path cachePath, TellusElevationSource.TileKey key, IOException cause) {
      try {
         Files.deleteIfExists(cachePath);
      } catch (IOException var5) {
         Tellus.LOGGER.debug("Failed to delete invalid elevation tile cache {}", cachePath, var5);
      }

      Tellus.LOGGER.debug("Ignoring invalid elevation tile {} at {}", new Object[]{key, cachePath, cause});
   }

   private double sampleBilinearAcrossTiles(int zoom, double globalX, double globalY, int baseTileX, int baseTileY, ShortRaster baseRaster) {
      int tilesPerAxis = 1 << zoom;
      int maxPixel = tilesPerAxis * TILE_SIZE - 1;
      double clampedX = Mth.clamp(globalX, 0.0, maxPixel);
      double clampedY = Mth.clamp(globalY, 0.0, maxPixel);
      int x0 = Mth.floor(clampedX);
      int y0 = Mth.floor(clampedY);
      int x1 = Math.min(x0 + 1, maxPixel);
      int y1 = Math.min(y0 + 1, maxPixel);
      double dx = clampedX - x0;
      double dy = clampedY - y0;
      double v00 = this.samplePixel(zoom, x0, y0, baseTileX, baseTileY, baseRaster);
      double v10 = this.samplePixel(zoom, x1, y0, baseTileX, baseTileY, baseRaster);
      double v01 = this.samplePixel(zoom, x0, y1, baseTileX, baseTileY, baseRaster);
      double v11 = this.samplePixel(zoom, x1, y1, baseTileX, baseTileY, baseRaster);
      if (!Double.isNaN(v00) && !Double.isNaN(v10) && !Double.isNaN(v01) && !Double.isNaN(v11)) {
         double lerpX0 = Mth.lerp(dx, v00, v10);
         double lerpX1 = Mth.lerp(dx, v01, v11);
         return Mth.lerp(dy, lerpX0, lerpX1);
      } else {
         double localX = clampedX - baseTileX * TILE_SIZE;
         double localY = clampedY - baseTileY * TILE_SIZE;
         return sampleBilinearLocal(baseRaster, localX, localY);
      }
   }

   private double sampleBilinearAcrossTilesLocalOnly(int zoom, double globalX, double globalY, int baseTileX, int baseTileY, ShortRaster baseRaster) {
      int tilesPerAxis = 1 << zoom;
      int maxPixel = tilesPerAxis * TILE_SIZE - 1;
      double clampedX = Mth.clamp(globalX, 0.0, maxPixel);
      double clampedY = Mth.clamp(globalY, 0.0, maxPixel);
      int x0 = Mth.floor(clampedX);
      int y0 = Mth.floor(clampedY);
      int x1 = Math.min(x0 + 1, maxPixel);
      int y1 = Math.min(y0 + 1, maxPixel);
      double dx = clampedX - x0;
      double dy = clampedY - y0;
      double v00 = this.samplePixelLocalOnly(zoom, x0, y0, baseTileX, baseTileY, baseRaster);
      double v10 = this.samplePixelLocalOnly(zoom, x1, y0, baseTileX, baseTileY, baseRaster);
      double v01 = this.samplePixelLocalOnly(zoom, x0, y1, baseTileX, baseTileY, baseRaster);
      double v11 = this.samplePixelLocalOnly(zoom, x1, y1, baseTileX, baseTileY, baseRaster);
      if (!Double.isNaN(v00) && !Double.isNaN(v10) && !Double.isNaN(v01) && !Double.isNaN(v11)) {
         double lerpX0 = Mth.lerp(dx, v00, v10);
         double lerpX1 = Mth.lerp(dx, v01, v11);
         return Mth.lerp(dy, lerpX0, lerpX1);
      } else {
         double localX = clampedX - baseTileX * TILE_SIZE;
         double localY = clampedY - baseTileY * TILE_SIZE;
         return sampleBilinearLocal(baseRaster, localX, localY);
      }
   }

   private double sampleBilinearAcrossTilesMemoryOnly(int zoom, double globalX, double globalY, int baseTileX, int baseTileY, ShortRaster baseRaster) {
      int tilesPerAxis = 1 << zoom;
      int maxPixel = tilesPerAxis * TILE_SIZE - 1;
      double clampedX = Mth.clamp(globalX, 0.0, maxPixel);
      double clampedY = Mth.clamp(globalY, 0.0, maxPixel);
      int x0 = Mth.floor(clampedX);
      int y0 = Mth.floor(clampedY);
      int x1 = Math.min(x0 + 1, maxPixel);
      int y1 = Math.min(y0 + 1, maxPixel);
      double dx = clampedX - x0;
      double dy = clampedY - y0;
      double v00 = this.samplePixelMemoryOnly(zoom, x0, y0, baseTileX, baseTileY, baseRaster);
      double v10 = this.samplePixelMemoryOnly(zoom, x1, y0, baseTileX, baseTileY, baseRaster);
      double v01 = this.samplePixelMemoryOnly(zoom, x0, y1, baseTileX, baseTileY, baseRaster);
      double v11 = this.samplePixelMemoryOnly(zoom, x1, y1, baseTileX, baseTileY, baseRaster);
      if (!Double.isNaN(v00) && !Double.isNaN(v10) && !Double.isNaN(v01) && !Double.isNaN(v11)) {
         double lerpX0 = Mth.lerp(dx, v00, v10);
         double lerpX1 = Mth.lerp(dx, v01, v11);
         return Mth.lerp(dy, lerpX0, lerpX1);
      } else {
         return Double.NaN;
      }
   }

   private double sampleOpenWatersBilinearAcrossTiles(
      int zoom, double globalX, double globalY, int baseTileX, int baseTileY, ShortRaster baseRaster
   ) {
      int tilesPerAxis = 1 << zoom;
      int maxPixel = tilesPerAxis * TILE_SIZE - 1;
      double clampedX = Mth.clamp(globalX, 0.0, maxPixel);
      double clampedY = Mth.clamp(globalY, 0.0, maxPixel);
      int x0 = Mth.floor(clampedX);
      int y0 = Mth.floor(clampedY);
      int x1 = Math.min(x0 + 1, maxPixel);
      int y1 = Math.min(y0 + 1, maxPixel);
      double dx = clampedX - x0;
      double dy = clampedY - y0;
      double v00 = this.sampleOpenWatersPixel(zoom, x0, y0, baseTileX, baseTileY, baseRaster);
      double v10 = this.sampleOpenWatersPixel(zoom, x1, y0, baseTileX, baseTileY, baseRaster);
      double v01 = this.sampleOpenWatersPixel(zoom, x0, y1, baseTileX, baseTileY, baseRaster);
      double v11 = this.sampleOpenWatersPixel(zoom, x1, y1, baseTileX, baseTileY, baseRaster);
      if (!Double.isNaN(v00) && !Double.isNaN(v10) && !Double.isNaN(v01) && !Double.isNaN(v11)) {
         double lerpX0 = Mth.lerp(dx, v00, v10);
         double lerpX1 = Mth.lerp(dx, v01, v11);
         return Mth.lerp(dy, lerpX0, lerpX1);
      } else {
         double localX = clampedX - baseTileX * TILE_SIZE;
         double localY = clampedY - baseTileY * TILE_SIZE;
         return sampleBilinearLocal(baseRaster, localX, localY);
      }
   }

   private double sampleOpenWatersBilinearAcrossTilesLocalOnly(
      int zoom, double globalX, double globalY, int baseTileX, int baseTileY, ShortRaster baseRaster
   ) {
      int tilesPerAxis = 1 << zoom;
      int maxPixel = tilesPerAxis * TILE_SIZE - 1;
      double clampedX = Mth.clamp(globalX, 0.0, maxPixel);
      double clampedY = Mth.clamp(globalY, 0.0, maxPixel);
      int x0 = Mth.floor(clampedX);
      int y0 = Mth.floor(clampedY);
      int x1 = Math.min(x0 + 1, maxPixel);
      int y1 = Math.min(y0 + 1, maxPixel);
      double dx = clampedX - x0;
      double dy = clampedY - y0;
      double v00 = this.sampleOpenWatersPixelLocalOnly(zoom, x0, y0, baseTileX, baseTileY, baseRaster);
      double v10 = this.sampleOpenWatersPixelLocalOnly(zoom, x1, y0, baseTileX, baseTileY, baseRaster);
      double v01 = this.sampleOpenWatersPixelLocalOnly(zoom, x0, y1, baseTileX, baseTileY, baseRaster);
      double v11 = this.sampleOpenWatersPixelLocalOnly(zoom, x1, y1, baseTileX, baseTileY, baseRaster);
      if (!Double.isNaN(v00) && !Double.isNaN(v10) && !Double.isNaN(v01) && !Double.isNaN(v11)) {
         double lerpX0 = Mth.lerp(dx, v00, v10);
         double lerpX1 = Mth.lerp(dx, v01, v11);
         return Mth.lerp(dy, lerpX0, lerpX1);
      } else {
         double localX = clampedX - baseTileX * TILE_SIZE;
         double localY = clampedY - baseTileY * TILE_SIZE;
         return sampleBilinearLocal(baseRaster, localX, localY);
      }
   }

   private double sampleOpenWatersBilinearAcrossTilesMemoryOnly(
      int zoom, double globalX, double globalY, int baseTileX, int baseTileY, ShortRaster baseRaster
   ) {
      int tilesPerAxis = 1 << zoom;
      int maxPixel = tilesPerAxis * TILE_SIZE - 1;
      double clampedX = Mth.clamp(globalX, 0.0, maxPixel);
      double clampedY = Mth.clamp(globalY, 0.0, maxPixel);
      int x0 = Mth.floor(clampedX);
      int y0 = Mth.floor(clampedY);
      int x1 = Math.min(x0 + 1, maxPixel);
      int y1 = Math.min(y0 + 1, maxPixel);
      double dx = clampedX - x0;
      double dy = clampedY - y0;
      double v00 = this.sampleOpenWatersPixelMemoryOnly(zoom, x0, y0, baseTileX, baseTileY, baseRaster);
      double v10 = this.sampleOpenWatersPixelMemoryOnly(zoom, x1, y0, baseTileX, baseTileY, baseRaster);
      double v01 = this.sampleOpenWatersPixelMemoryOnly(zoom, x0, y1, baseTileX, baseTileY, baseRaster);
      double v11 = this.sampleOpenWatersPixelMemoryOnly(zoom, x1, y1, baseTileX, baseTileY, baseRaster);
      if (!Double.isNaN(v00) && !Double.isNaN(v10) && !Double.isNaN(v01) && !Double.isNaN(v11)) {
         double lerpX0 = Mth.lerp(dx, v00, v10);
         double lerpX1 = Mth.lerp(dx, v01, v11);
         return Mth.lerp(dy, lerpX0, lerpX1);
      } else {
         return Double.NaN;
      }
   }

   private double samplePixel(int zoom, int pixelX, int pixelY, int baseTileX, int baseTileY, ShortRaster baseRaster) {
      int tileX = Math.floorDiv(pixelX, TILE_SIZE);
      int tileY = Math.floorDiv(pixelY, TILE_SIZE);
      ShortRaster raster = tileX == baseTileX && tileY == baseTileY ? baseRaster : this.getTile(new TellusElevationSource.TileKey(zoom, tileX, tileY));
      if (raster == null) {
         return Double.NaN;
      } else {
         int localX = pixelX - tileX * TILE_SIZE;
         int localY = pixelY - tileY * TILE_SIZE;
         return raster.get(localX, localY);
      }
   }

   private double samplePixelLocalOnly(int zoom, int pixelX, int pixelY, int baseTileX, int baseTileY, ShortRaster baseRaster) {
      int tileX = Math.floorDiv(pixelX, TILE_SIZE);
      int tileY = Math.floorDiv(pixelY, TILE_SIZE);
      ShortRaster raster = tileX == baseTileX && tileY == baseTileY
         ? baseRaster
         : this.getTileLocalOnly(new TellusElevationSource.TileKey(zoom, tileX, tileY));
      if (raster == null) {
         return Double.NaN;
      } else {
         int localX = pixelX - tileX * TILE_SIZE;
         int localY = pixelY - tileY * TILE_SIZE;
         return raster.get(localX, localY);
      }
   }

   private double samplePixelMemoryOnly(int zoom, int pixelX, int pixelY, int baseTileX, int baseTileY, ShortRaster baseRaster) {
      int tileX = Math.floorDiv(pixelX, TILE_SIZE);
      int tileY = Math.floorDiv(pixelY, TILE_SIZE);
      ShortRaster raster = tileX == baseTileX && tileY == baseTileY
         ? baseRaster
         : this.getTileMemoryOnly(new TellusElevationSource.TileKey(zoom, tileX, tileY));
      if (raster == null) {
         return Double.NaN;
      } else {
         int localX = pixelX - tileX * TILE_SIZE;
         int localY = pixelY - tileY * TILE_SIZE;
         return raster.get(localX, localY);
      }
   }

   private double sampleOpenWatersPixel(int zoom, int pixelX, int pixelY, int baseTileX, int baseTileY, ShortRaster baseRaster) {
      int tileX = Math.floorDiv(pixelX, TILE_SIZE);
      int tileY = Math.floorDiv(pixelY, TILE_SIZE);
      ShortRaster raster = tileX == baseTileX && tileY == baseTileY
         ? baseRaster
         : this.getOpenWatersTile(new TellusElevationSource.TileKey(zoom, tileX, tileY));
      if (raster == null) {
         return Double.NaN;
      } else {
         int localX = pixelX - tileX * TILE_SIZE;
         int localY = pixelY - tileY * TILE_SIZE;
         return raster.get(localX, localY);
      }
   }

   private double sampleOpenWatersPixelLocalOnly(
      int zoom, int pixelX, int pixelY, int baseTileX, int baseTileY, ShortRaster baseRaster
   ) {
      int tileX = Math.floorDiv(pixelX, TILE_SIZE);
      int tileY = Math.floorDiv(pixelY, TILE_SIZE);
      ShortRaster raster = tileX == baseTileX && tileY == baseTileY
         ? baseRaster
         : this.getOpenWatersTileLocalOnly(new TellusElevationSource.TileKey(zoom, tileX, tileY));
      if (raster == null) {
         return Double.NaN;
      } else {
         int localX = pixelX - tileX * TILE_SIZE;
         int localY = pixelY - tileY * TILE_SIZE;
         return raster.get(localX, localY);
      }
   }

   private double sampleOpenWatersPixelMemoryOnly(
      int zoom, int pixelX, int pixelY, int baseTileX, int baseTileY, ShortRaster baseRaster
   ) {
      int tileX = Math.floorDiv(pixelX, TILE_SIZE);
      int tileY = Math.floorDiv(pixelY, TILE_SIZE);
      ShortRaster raster = tileX == baseTileX && tileY == baseTileY
         ? baseRaster
         : this.getOpenWatersTileMemoryOnly(new TellusElevationSource.TileKey(zoom, tileX, tileY));
      if (raster == null) {
         return Double.NaN;
      } else {
         int localX = pixelX - tileX * TILE_SIZE;
         int localY = pixelY - tileY * TILE_SIZE;
         return raster.get(localX, localY);
      }
   }

   private static double sampleBilinearLocal(ShortRaster raster, double x, double y) {
      int maxX = raster.width() - 1;
      int maxY = raster.height() - 1;
      int x0 = Mth.clamp(Mth.floor(x), 0, maxX);
      int y0 = Mth.clamp(Mth.floor(y), 0, maxY);
      int x1 = Math.min(x0 + 1, maxX);
      int y1 = Math.min(y0 + 1, maxY);
      double dx = x - x0;
      double dy = y - y0;
      double v00 = raster.get(x0, y0);
      double v10 = raster.get(x1, y0);
      double v01 = raster.get(x0, y1);
      double v11 = raster.get(x1, y1);
      double lerpX0 = Mth.lerp(dx, v00, v10);
      double lerpX1 = Mth.lerp(dx, v01, v11);
      return Mth.lerp(dy, lerpX0, lerpX1);
   }

   private static int selectZoom(double worldScale) {
      double zoom = zoomForScale(worldScale);
      return Math.max((int)Math.round(zoom), 0);
   }

   static int selectPreviewZoom(double worldScale, double previewResolutionMeters, int maxZoom) {
      double resolutionMeters = effectiveSampleResolutionMeters(worldScale, previewResolutionMeters);
      return Mth.clamp(selectZoom(resolutionMeters), MIN_ZOOM, maxZoom);
   }

   private static TellusElevationSource.ElevationDiagnostic diagnostic(TellusElevationSource.ResolvedElevationSample sample) {
      return diagnostic(
         sample.elevationMeters(), sample.primaryProvider(), sample.providerMask(), sample.sourceResolutionMeters()
      );
   }

   private static TellusElevationSource.ElevationDiagnostic diagnostic(
      double elevation, TellusElevationSource.DemUsage primaryProvider, int providerMask, double sourceResolutionMeters
   ) {
      double resolvedResolution = Double.isFinite(sourceResolutionMeters) && sourceResolutionMeters > 0.0
         ? sourceResolutionMeters
         : primaryProvider.nominalResolutionMeters();
      return new TellusElevationSource.ElevationDiagnostic(elevation, primaryProvider, providerMask, resolvedResolution);
   }

   static boolean shouldUseBathymetry(boolean oceanMask, double mapterhornElevationMeters) {
      return oceanMask
         && (!Double.isFinite(mapterhornElevationMeters)
            || mapterhornElevationMeters <= MAPTERHORN_SEA_LEVEL_THRESHOLD_METERS);
   }

   static double resolveElevationMeters(
      boolean oceanMask,
      double mapterhornElevationMeters,
      Supplier<Double> oceanSupplier,
      double missingFallbackElevation
   ) {
      if (shouldUseBathymetry(oceanMask, mapterhornElevationMeters)) {
         double oceanElevation = oceanSupplier.get();
         if (Double.isFinite(oceanElevation)) {
            return oceanElevation;
         }
      }

      return Double.isFinite(mapterhornElevationMeters)
         ? mapterhornElevationMeters
         : missingFallbackElevation;
   }

   static boolean isUsablePreviewElevation(TellusElevationSource.ResolvedElevationSample sample) {
      return sample != null
         && Double.isFinite(sample.elevationMeters())
         && (sample.mapterhornAvailable() || sample.primaryProvider() != TellusElevationSource.DemUsage.TERRAIN_TILES);
   }

   private static TellusElevationSource.ResolvedElevationSample requireUsablePreviewElevation(
      TellusElevationSource.ResolvedElevationSample sample
   ) {
      return isUsablePreviewElevation(sample) ? sample : missingResolvedElevation(Double.NaN);
   }

   private TellusElevationSource.ResolvedElevationSample resolveElevationSample(
      double mapterhornElevationMeters,
      boolean oceanMask,
      double mapterhornResolutionMeters,
      Supplier<TellusElevationSource.OceanElevationSample> oceanSupplier,
      double missingFallbackElevation
   ) {
      boolean mapterhornAvailable = Double.isFinite(mapterhornElevationMeters);
      TellusElevationSource.OceanElevationSample[] sampledOcean = new TellusElevationSource.OceanElevationSample[1];
      double elevation = resolveElevationMeters(
         oceanMask,
         mapterhornElevationMeters,
         () -> {
            TellusElevationSource.OceanElevationSample ocean = Objects.requireNonNull(oceanSupplier.get(), "oceanElevationSample");
            sampledOcean[0] = ocean;
            return ocean.elevation();
         },
         missingFallbackElevation
      );
      TellusElevationSource.OceanElevationSample ocean = sampledOcean[0];
      if (ocean != null && Double.isFinite(ocean.elevation())) {
         return new TellusElevationSource.ResolvedElevationSample(
            elevation,
            ocean.usage(),
            ocean.usage().bit(),
            ocean.usage().nominalResolutionMeters(),
            mapterhornAvailable,
            mapterhornElevationMeters
         );
      }

      return resolvedMapterhorn(
         mapterhornElevationMeters, mapterhornAvailable, mapterhornResolutionMeters, missingFallbackElevation
      );
   }

   private static TellusElevationSource.ResolvedElevationSample resolvedMapterhorn(
      double mapterhornElevationMeters,
      boolean mapterhornAvailable,
      double resolutionMeters,
      double missingFallbackElevation
   ) {
      double elevation = mapterhornAvailable ? mapterhornElevationMeters : missingFallbackElevation;
      return new TellusElevationSource.ResolvedElevationSample(
         elevation,
         TellusElevationSource.DemUsage.TERRAIN_TILES,
         TellusElevationSource.DemUsage.TERRAIN_TILES.bit(),
         resolutionMeters,
         mapterhornAvailable,
         mapterhornElevationMeters
      );
   }

   private static TellusElevationSource.ResolvedElevationSample missingResolvedElevation(double elevation) {
      return resolvedMapterhorn(
         Double.NaN, false, TellusElevationSource.DemUsage.TERRAIN_TILES.nominalResolutionMeters(), elevation
      );
   }

   private static EarthGeneratorSettings.DemSelection lockedDemSelection(EarthGeneratorSettings.DemSelection ignored) {
      return EarthGeneratorSettings.DemSelection.mapterhornSelection();
   }

   private static double zoomForScale(double meters) {
      return Math.log(EQUATOR_CIRCUMFERENCE / (TILE_SIZE * meters)) / Math.log(2.0);
   }

   static ShortRaster readTerrainRaster(InputStream input) throws IOException {
      BufferedImage image;
      try (ImageInputStream imageInput = new MemoryCacheImageInputStream(input)) {
         // ImageIO only discovers plug-ins once per registry. If another mod initializes it
         // under a classloader that cannot see Tellus's nested jars, the WebP SPI stays absent.
         ImageReader reader = WEBP_READER_SPI.createReaderInstance();
         try {
            reader.setInput(imageInput, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width <= 0 || height <= 0 || width > TILE_SIZE || height > TILE_SIZE) {
               throw new IOException(
                  "Terrarium tile dimensions " + width + "x" + height + " exceed the " + TILE_SIZE + "x" + TILE_SIZE + " safety limit"
               );
            }
            image = reader.read(0);
         } finally {
            reader.dispose();
         }
      }
      if (image == null) {
         throw new IOException("Invalid Terrarium WebP tile");
      } else {
         int width = image.getWidth();
         int height = image.getHeight();
         ShortRaster raster = ShortRaster.create(width, height);
         int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);

         for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
               int argb = pixels[x + y * width];
               int red = argb >> 16 & 0xFF;
               int green = argb >> 8 & 0xFF;
               int blue = argb & 0xFF;
               double elevation = red * TERRARIUM_ENCODING_SCALE
                  + green
                  + blue / (double)TERRARIUM_ENCODING_SCALE
                  - 32768.0;
               raster.set(x, y, (short)Math.round(elevation));
            }
         }

         return raster;
      }
   }

   private static ShortRaster readCachedTerrainRaster(Path path) throws IOException {
      long size = Files.size(path);
      if (size > MAX_TILE_BYTES) {
         throw new IOException("Cached elevation tile exceeds the " + MAX_TILE_BYTES + " byte safety limit");
      }
      try (InputStream input = Files.newInputStream(path)) {
         return readTerrainRaster(input);
      }
   }

   @Override
   public TellusCacheDomain cacheDomain() {
      return TellusCacheDomain.TERRAIN;
   }

   @Override
   public boolean matchesCacheDomain(TellusCacheDomain domain) {
      return domain == TellusCacheDomain.TERRAIN || domain == TellusCacheDomain.OPENWATERS;
   }

   @Override
   public void clearCache() {
      this.cache.invalidateAll();
      this.oceanCache.invalidateAll();
      this.mapterhornResolutionSource.clearCache();
      this.cache.cleanUp();
      this.oceanCache.cleanUp();
   }

   private record TileKey(int zoom, int x, int y) {
   }

   private record AreaSample(double blockX, double blockZ) {
   }

   private record RawTileRequest(TellusElevationSource.TileKey key, boolean openWaters) {
      private String label() {
         return (this.openWaters ? "OpenWaters " : "Mapterhorn ") + this.key.zoom() + "/" + this.key.x() + "/" + this.key.y();
      }
   }

   private record TerrainTileBounds(int zoom, List<TellusElevationSource.TileXRange> xRanges, int minY, int maxY) {
      private int count() {
         long width = this.xRanges.stream().mapToLong(TellusElevationSource.TileXRange::count).sum();
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

   public static enum DemUsage {
      TERRAIN_TILES("terrarium", 1),
      OPENWATERS("openwaters", 1 << 1);

      private final String providerId;
      private final int bit;

      private DemUsage(String providerId, int bit) {
         this.providerId = Objects.requireNonNull(providerId, "providerId");
         this.bit = bit;
      }

      public int bit() {
         return this.bit;
      }

      public String providerId() {
         return this.providerId;
      }

      public double nominalResolutionMeters() {
         return switch (this) {
            case TERRAIN_TILES -> 30.0;
            case OPENWATERS -> OPENWATERS_NOMINAL_RESOLUTION_METERS;
         };
      }

      public Component label() {
         return Component.translatable("property.tellus.dem_provider.value." + this.providerId);
      }
   }

   public record ElevationDiagnostic(
      double elevation, TellusElevationSource.DemUsage primaryProvider, int providerMask, double sourceResolutionMeters
   ) {
      public ElevationDiagnostic(
         double elevation, TellusElevationSource.DemUsage primaryProvider, int providerMask, double sourceResolutionMeters
      ) {
         this.elevation = elevation;
         this.primaryProvider = Objects.requireNonNull(primaryProvider, "primaryProvider");
         this.providerMask = providerMask;
         this.sourceResolutionMeters = sourceResolutionMeters;
      }

      public boolean usesMultipleProviders() {
         return Integer.bitCount(this.providerMask) > 1;
      }

      public double displayResolutionMeters() {
         return Double.isFinite(this.sourceResolutionMeters) && this.sourceResolutionMeters > 0.0
            ? this.sourceResolutionMeters
            : this.primaryProvider.nominalResolutionMeters();
      }
   }

   public record ResolvedElevationSample(
      double elevationMeters,
      TellusElevationSource.DemUsage primaryProvider,
      int providerMask,
      double sourceResolutionMeters,
      boolean mapterhornAvailable,
      double mapterhornElevationMeters
   ) {
      public ResolvedElevationSample {
         Objects.requireNonNull(primaryProvider, "primaryProvider");
         if (!mapterhornAvailable) {
            mapterhornElevationMeters = Double.NaN;
         }
      }

      public boolean mapterhornLandOverride() {
         return this.mapterhornAvailable
            && this.mapterhornElevationMeters > MAPTERHORN_SEA_LEVEL_THRESHOLD_METERS;
      }
   }

   private record OceanElevationSample(double elevation, TellusElevationSource.DemUsage usage) {
      private static TellusElevationSource.OceanElevationSample none() {
         return new TellusElevationSource.OceanElevationSample(Double.NaN, TellusElevationSource.DemUsage.TERRAIN_TILES);
      }
   }

}
