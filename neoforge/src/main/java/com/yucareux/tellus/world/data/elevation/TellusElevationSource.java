package com.yucareux.tellus.world.data.elevation;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.cache.TellusCacheDomain;
import com.yucareux.tellus.cache.TellusCacheHandle;
import com.yucareux.tellus.cache.TellusCacheRegistry;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.world.data.mask.TellusLandMaskSource;
import com.yucareux.tellus.world.data.source.DownloadProgressReporter;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.EarthProjection;
import com.yucareux.tellus.worldgen.TellusWorldgenSources;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import net.neoforged.fml.loading.FMLPaths;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

public final class TellusElevationSource implements TellusCacheHandle {
   private static final double EQUATOR_CIRCUMFERENCE = 4.0075017E7;
   private static final int TILE_SIZE = 256;
   private static final int MIN_ZOOM = 0;
   private static final int LAND_MAX_ZOOM = 15;
   private static final int OCEAN_MAX_ZOOM = 10;
   private static final double MIN_LAT = -85.05112878;
   private static final double MAX_LAT = 85.05112878;
   private static final double MIN_LON = -180.0;
   private static final double MAX_LON = 180.0;
   private static final double POLAR_NORTH_MIN_LAT = 60.0;
   private static final double POLAR_SOUTH_MAX_LAT = -60.0;
   private static final double RESOLUTION_METERS = 30.0;
   private static final String ENDPOINT = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium";
   private static final int MAX_CACHE_TILES = intProperty("tellus.elevation.cacheTiles", 512);
   // The normalized cache currently incurs a very expensive first-build path on cache misses.
   // Keep it opt-in until the ingest/build cost is low enough for preview and spawn-time terrain reads.
   private static final boolean NORMALIZED_ENABLED = booleanProperty("tellus.elevation.normalized.enabled", false);
   private static final boolean NORMALIZED_COMPARE = booleanProperty("tellus.elevation.normalized.compare", false);
   private static final int NORMALIZED_COMPARE_LOG_LIMIT = intProperty("tellus.elevation.normalized.compareLogLimit", 100);
   private static final boolean DEBUG_DEM = Boolean.getBoolean("tellus.debug.dem");
   private static final ShortRaster MISSING_RASTER = ShortRaster.create(1, 1);
   private static final NormalizedElevationCache NORMALIZED_CACHE = NORMALIZED_ENABLED ? new NormalizedElevationCache() : null;
   private static final AtomicInteger NORMALIZED_COMPARE_LOGGED = new AtomicInteger();
   private final Path cacheRoot;
   private final LoadingCache<TellusElevationSource.TileKey, ShortRaster> cache;
   private final ArcticDemElevationSource arcticDem = new ArcticDemElevationSource();
   private final RemaElevationSource rema = new RemaElevationSource();
   private final SwissAlti3dElevationSource swissAlti3d = new SwissAlti3dElevationSource();
   private final AhnElevationSource ahn = new AhnElevationSource();
   private final CanElevationSource canElevation = new CanElevationSource();
   private final NorwayDtm1ElevationSource norwayDtm1 = new NorwayDtm1ElevationSource();
   private final JapanGsiElevationSource japanGsi = new JapanGsiElevationSource();
   private final Usgs3depElevationSource usgs = new Usgs3depElevationSource();
   private final CopernicusDemElevationSource copernicus = new CopernicusDemElevationSource();
   private final TerrainTilesResolutionIndex terrainResolutionIndex = TerrainTilesResolutionIndex.create();
   private final TellusLandMaskSource landMask = TellusWorldgenSources.landMask();
   private volatile EarthGeneratorSettings.DemSelection lastLoggedSelection;

   public TellusElevationSource() {
      this.cacheRoot = FMLPaths.GAMEDIR.get().resolve("tellus/cache/elevation-tellus");
      this.cache = CacheBuilder.newBuilder().maximumSize(MAX_CACHE_TILES).build(new CacheLoader<TellusElevationSource.TileKey, ShortRaster>() {
         public ShortRaster load( TellusElevationSource.TileKey key) throws Exception {
            return TellusElevationSource.this.loadTile(key);
         }
      });
      TellusCacheRegistry.register(this);
   }

   public double sampleElevationMeters(double blockX, double blockZ, double worldScale) {
      return this.sampleElevationMeters(blockX, blockZ, worldScale, true, EarthGeneratorSettings.DEFAULT.demSelection());
   }

   public double sampleElevationMeters(double blockX, double blockZ, double worldScale, boolean highResOcean) {
      return this.sampleElevationMeters(blockX, blockZ, worldScale, highResOcean, EarthGeneratorSettings.DEFAULT.demSelection());
   }

   public double sampleElevationMeters(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection
   ) {
      return this.sampleElevationMeters(blockX, blockZ, worldScale, highResOcean, demSelection, worldScale);
   }

   public double samplePreviewElevationMeters(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      return this.sampleElevationMeters(blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters);
   }

   public double samplePreviewElevationMetersLocalOnly(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      if (worldScale <= 0.0) {
         return 0.0;
      } else {
         return this.sampleElevationMetersFromProvidersLocalOnly(blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters);
      }
   }

   public double samplePreviewElevationMetersMemoryOnly(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      if (worldScale <= 0.0) {
         return Double.NaN;
      } else {
         return this.sampleElevationMetersFromProvidersMemoryOnly(blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters);
      }
   }

   public double samplePreviewTerrainTilesMetersLocalOnly(
      double blockX, double blockZ, double worldScale, boolean highResOcean, double previewResolutionMeters
   ) {
      return this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
   }

   private double sampleElevationMeters(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      if (worldScale <= 0.0) {
         return 0.0;
      } else {
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
               TellusElevationSource.ElevationDiagnostic normalized = this.sampleDiagnosticFromNormalizedCache(
                  blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters
               );
               this.compareNormalizedElevation(blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters, normalized.elevation());
               return normalized.elevation();
            } catch (RuntimeException error) {
               Tellus.LOGGER.debug("Falling back to direct DEM sampling for normalized cache miss at {},{}", blockX, blockZ, error);
            }
         }

         return this.sampleElevationMetersFromProviders(blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters);
      }
   }

   private double sampleElevationMetersFromProviders(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      return demSelection.isAllEnabled()
         ? this.sampleElevationMetersFromLegacyProvider(
            blockX, blockZ, worldScale, highResOcean, EarthGeneratorSettings.DemProvider.AUTO, previewResolutionMeters
         )
         : this.sampleFilteredElevationMeters(blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters);
   }

   private double sampleElevationMetersFromProvidersLocalOnly(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      return demSelection.isAllEnabled()
         ? this.sampleElevationMetersFromLegacyProviderLocalOnly(
            blockX, blockZ, worldScale, highResOcean, EarthGeneratorSettings.DemProvider.AUTO, previewResolutionMeters
         )
         : this.sampleFilteredElevationMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters);
   }

   private double sampleElevationMetersFromProvidersMemoryOnly(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      return this.sampleTerrariumMetersMemoryOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
   }

   private double sampleElevationMetersFromLegacyProvider(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemProvider demProvider,
      double previewResolutionMeters
   ) {
      return switch (demProvider) {
            case AUTO -> this.sampleAutomaticMeters(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            case SWISSALTI3D -> {
               SwissAlti3dElevationSource.Sample swiss = this.swissAlti3d.sample(blockX, blockZ, worldScale, previewResolutionMeters);
               yield swiss.usable() ? swiss.elevation() : this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            }
            case AHN -> {
               AhnElevationSource.Sample ahn = this.ahn.sample(blockX, blockZ, worldScale);
               yield ahn.usable() ? ahn.elevation() : this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            }
            case CANELEVATION -> {
               CanElevationSource.Sample canada = this.canElevation.sample(blockX, blockZ, worldScale, previewResolutionMeters);
               yield canada.usable() ? canada.elevation() : this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            }
            case NORWAYDTM1 -> {
               NorwayDtm1ElevationSource.Sample norway = this.norwayDtm1.sample(blockX, blockZ, worldScale, previewResolutionMeters);
               yield norway.usable() ? norway.elevation() : this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            }
            case JAPANGSI -> {
               JapanGsiElevationSource.Sample japan = this.japanGsi.sample(blockX, blockZ, worldScale, previewResolutionMeters);
               yield japan.usable()
                  ? japan.elevation()
                  : this.sampleAutomaticMeters(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters, false);
            }
            case USGS -> this.sampleUsgsMetersWithFallback(blockX, blockZ, worldScale, highResOcean);
            case COPERNICUS -> this.sampleCopernicusPreferredMeters(blockX, blockZ, worldScale, highResOcean);
            case ARCTICDEM -> {
               TellusElevationSource.PolarDemSample polar = this.samplePolarDem(blockX, blockZ, worldScale);
               yield polar.usable() ? polar.elevation() : this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            }
            case TERRARIUM -> this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
         };
   }

   private double sampleElevationMetersFromLegacyProviderLocalOnly(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemProvider demProvider,
      double previewResolutionMeters
   ) {
      return switch (demProvider) {
            case AUTO -> this.sampleAutomaticMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            case SWISSALTI3D -> {
               SwissAlti3dElevationSource.Sample swiss = this.swissAlti3d.sampleLocalOnly(blockX, blockZ, worldScale, previewResolutionMeters);
               yield swiss.usable()
                  ? swiss.elevation()
                  : this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            }
            case AHN -> {
               AhnElevationSource.Sample ahn = this.ahn.sampleLocalOnly(blockX, blockZ, worldScale);
               yield ahn.usable()
                  ? ahn.elevation()
                  : this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            }
            case CANELEVATION -> {
               CanElevationSource.Sample canada = this.canElevation.sampleLocalOnly(blockX, blockZ, worldScale, previewResolutionMeters);
               yield canada.usable()
                  ? canada.elevation()
                  : this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            }
            case NORWAYDTM1 -> {
               NorwayDtm1ElevationSource.Sample norway = this.norwayDtm1.sampleLocalOnly(blockX, blockZ, worldScale, previewResolutionMeters);
               yield norway.usable()
                  ? norway.elevation()
                  : this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            }
            case JAPANGSI -> {
               JapanGsiElevationSource.Sample japan = this.japanGsi.sampleLocalOnly(blockX, blockZ, worldScale, previewResolutionMeters);
               yield japan.usable()
                  ? japan.elevation()
                  : this.sampleAutomaticMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters, false);
            }
            case USGS -> this.sampleUsgsMetersWithFallbackLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            case COPERNICUS -> this.sampleCopernicusPreferredMetersLocalOnly(
               blockX, blockZ, worldScale, highResOcean, previewResolutionMeters
            );
            case ARCTICDEM -> {
               TellusElevationSource.PolarDemSample polar = this.samplePolarDemLocalOnly(blockX, blockZ, worldScale);
               yield polar.usable()
                  ? polar.elevation()
                  : this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            }
            case TERRARIUM -> this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
         };
   }

   public TellusElevationSource.ElevationDiagnostic sampleDiagnostic(
      double blockX, double blockZ, double worldScale, boolean highResOcean, EarthGeneratorSettings.DemSelection demSelection
   ) {
      return this.sampleDiagnostic(blockX, blockZ, worldScale, highResOcean, demSelection, worldScale);
   }

   public TellusElevationSource.ElevationDiagnostic samplePreviewDiagnostic(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      return this.sampleDiagnostic(blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters);
   }

   private TellusElevationSource.ElevationDiagnostic sampleDiagnostic(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      if (worldScale <= 0.0) {
         return diagnostic(0.0, TellusElevationSource.DemUsage.TERRAIN_TILES);
      } else {
         if (NORMALIZED_ENABLED) {
            try {
               TellusElevationSource.ElevationDiagnostic normalized = this.sampleDiagnosticFromNormalizedCache(
                  blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters
               );
               this.compareNormalizedElevation(blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters, normalized.elevation());
               return normalized;
            } catch (RuntimeException error) {
               Tellus.LOGGER.debug("Falling back to direct DEM diagnostics for normalized cache miss at {},{}", blockX, blockZ, error);
            }
         }

         return this.sampleDiagnosticFromProviders(blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters);
      }
   }

   private TellusElevationSource.ElevationDiagnostic sampleDiagnosticFromProviders(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      return demSelection.isAllEnabled()
         ? this.sampleDiagnosticFromLegacyProvider(
            blockX, blockZ, worldScale, highResOcean, EarthGeneratorSettings.DemProvider.AUTO, previewResolutionMeters
         )
         : this.sampleFilteredDiagnostic(blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters);
   }

   private TellusElevationSource.ElevationDiagnostic sampleDiagnosticFromLegacyProvider(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemProvider demProvider,
      double previewResolutionMeters
   ) {
      return switch (demProvider) {
            case AUTO -> this.sampleAutomaticDiagnostic(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            case SWISSALTI3D -> {
               SwissAlti3dElevationSource.Sample swiss = this.swissAlti3d.sample(blockX, blockZ, worldScale, previewResolutionMeters);
               yield swiss.usable()
                  ? diagnostic(swiss.elevation(), swiss.usage(), swiss.usage().bit(), swiss.resolutionMeters())
                  : this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            }
            case AHN -> {
               AhnElevationSource.Sample ahn = this.ahn.sample(blockX, blockZ, worldScale);
               yield ahn.usable()
                  ? diagnostic(ahn.elevation(), ahn.usage(), ahn.usage().bit(), ahn.resolutionMeters())
                  : this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            }
            case CANELEVATION -> {
               CanElevationSource.Sample canada = this.canElevation.sample(blockX, blockZ, worldScale, previewResolutionMeters);
               yield canada.usable()
                  ? diagnostic(canada.elevation(), canada.usage(), canada.usage().bit(), canada.resolutionMeters())
                  : this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            }
            case NORWAYDTM1 -> {
               NorwayDtm1ElevationSource.Sample norway = this.norwayDtm1.sample(blockX, blockZ, worldScale, previewResolutionMeters);
               yield norway.usable()
                  ? diagnostic(norway.elevation(), norway.usage(), norway.usage().bit(), norway.resolutionMeters())
                  : this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            }
            case JAPANGSI -> {
               JapanGsiElevationSource.Sample japan = this.japanGsi.sample(blockX, blockZ, worldScale, previewResolutionMeters);
               yield japan.usable()
                  ? diagnostic(japan.elevation(), japan.usage(), japan.usage().bit(), japan.resolutionMeters())
                  : this.sampleAutomaticDiagnostic(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters, false);
            }
            case USGS -> this.sampleUsgsMetersWithFallbackDiagnostic(blockX, blockZ, worldScale, highResOcean);
            case COPERNICUS -> this.sampleCopernicusPreferredDiagnostic(blockX, blockZ, worldScale, highResOcean);
            case ARCTICDEM -> {
               TellusElevationSource.PolarDemSample polar = this.samplePolarDem(blockX, blockZ, worldScale);
               yield polar.usable()
                  ? diagnostic(polar.elevation(), polar.usage())
                  : this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            }
            case TERRARIUM -> this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
         };
   }

   private double sampleFilteredElevationMeters(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.SWISSALTI3D)) {
         SwissAlti3dElevationSource.Sample swiss = this.swissAlti3d.sample(blockX, blockZ, worldScale, previewResolutionMeters);
         if (swiss.usable()) {
            return swiss.elevation();
         }
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.AHN)) {
         AhnElevationSource.Sample ahn = this.ahn.sample(blockX, blockZ, worldScale);
         if (ahn.usable()) {
            return ahn.elevation();
         }
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.CANELEVATION)) {
         CanElevationSource.Sample canada = this.canElevation.sample(blockX, blockZ, worldScale, previewResolutionMeters);
         if (canada.usable()) {
            return canada.elevation();
         }
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.NORWAYDTM1)) {
         NorwayDtm1ElevationSource.Sample norway = this.norwayDtm1.sample(blockX, blockZ, worldScale, previewResolutionMeters);
         if (norway.usable()) {
            return norway.elevation();
         }
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.JAPANGSI)) {
         JapanGsiElevationSource.Sample japan = this.japanGsi.sample(blockX, blockZ, worldScale, previewResolutionMeters);
         if (japan.usable()) {
            return japan.elevation();
         }
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.ARCTICDEM)) {
         TellusElevationSource.PolarDemSample polar = this.samplePolarDem(blockX, blockZ, worldScale);
         if (polar.usable()) {
            return polar.elevation();
         }
      }

      TellusElevationSource.AutoDecision decision = this.autoDecision(blockX, blockZ, worldScale);
      if (decision.preferUsgs()) {
         if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.USGS)) {
            double usgsSample = this.sampleUsgsPreferredMeters(blockX, blockZ, worldScale, highResOcean, decision.landMaskSample());
            if (!Double.isNaN(usgsSample)) {
               return usgsSample;
            }
         }

         if (demSelection.copernicusEnabled()) {
            double copernicusSample = this.sampleCopernicusPreferredMeters(
               blockX, blockZ, worldScale, highResOcean, decision.landMaskSample(), demSelection.terrainTilesEnabled()
            );
            if (!Double.isNaN(copernicusSample)) {
               return copernicusSample;
            }
         }
      } else if (decision.preferCopernicus() && demSelection.copernicusEnabled()) {
         double copernicusSample = this.sampleCopernicusPreferredMeters(
            blockX, blockZ, worldScale, highResOcean, decision.landMaskSample(), demSelection.terrainTilesEnabled()
         );
         if (!Double.isNaN(copernicusSample)) {
            return copernicusSample;
         }
      }

      return this.sampleFinalFallbackMeters(blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters);
   }

   private double sampleFilteredElevationMetersLocalOnly(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.SWISSALTI3D)) {
         SwissAlti3dElevationSource.Sample swiss = this.swissAlti3d.sampleLocalOnly(blockX, blockZ, worldScale, previewResolutionMeters);
         if (swiss.usable()) {
            return swiss.elevation();
         }
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.AHN)) {
         AhnElevationSource.Sample ahn = this.ahn.sampleLocalOnly(blockX, blockZ, worldScale);
         if (ahn.usable()) {
            return ahn.elevation();
         }
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.CANELEVATION)) {
         CanElevationSource.Sample canada = this.canElevation.sampleLocalOnly(blockX, blockZ, worldScale, previewResolutionMeters);
         if (canada.usable()) {
            return canada.elevation();
         }
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.NORWAYDTM1)) {
         NorwayDtm1ElevationSource.Sample norway = this.norwayDtm1.sampleLocalOnly(blockX, blockZ, worldScale, previewResolutionMeters);
         if (norway.usable()) {
            return norway.elevation();
         }
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.JAPANGSI)) {
         JapanGsiElevationSource.Sample japan = this.japanGsi.sampleLocalOnly(blockX, blockZ, worldScale, previewResolutionMeters);
         if (japan.usable()) {
            return japan.elevation();
         }
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.ARCTICDEM)) {
         TellusElevationSource.PolarDemSample polar = this.samplePolarDemLocalOnly(blockX, blockZ, worldScale);
         if (polar.usable()) {
            return polar.elevation();
         }
      }

      TellusElevationSource.AutoDecision decision = this.autoDecisionLocalOnly(blockX, blockZ, worldScale);
      if (decision.preferUsgs()) {
         if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.USGS)) {
            double usgsSample = this.sampleUsgsPreferredMetersLocalOnly(
               blockX, blockZ, worldScale, highResOcean, decision.landMaskSample(), previewResolutionMeters
            );
            if (!Double.isNaN(usgsSample)) {
               return usgsSample;
            }
         }

         if (demSelection.copernicusEnabled()) {
            double copernicusSample = this.sampleCopernicusPreferredMetersLocalOnly(
               blockX, blockZ, worldScale, highResOcean, decision.landMaskSample(), demSelection.terrainTilesEnabled(), previewResolutionMeters
            );
            if (!Double.isNaN(copernicusSample)) {
               return copernicusSample;
            }
         }
      } else if (decision.preferCopernicus() && demSelection.copernicusEnabled()) {
         double copernicusSample = this.sampleCopernicusPreferredMetersLocalOnly(
            blockX, blockZ, worldScale, highResOcean, decision.landMaskSample(), demSelection.terrainTilesEnabled(), previewResolutionMeters
         );
         if (!Double.isNaN(copernicusSample)) {
            return copernicusSample;
         }
      }

      return this.sampleFinalFallbackMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters);
   }

   private TellusElevationSource.ElevationDiagnostic sampleFilteredDiagnostic(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.SWISSALTI3D)) {
         SwissAlti3dElevationSource.Sample swiss = this.swissAlti3d.sample(blockX, blockZ, worldScale, previewResolutionMeters);
         if (swiss.usable()) {
            return diagnostic(swiss.elevation(), swiss.usage(), swiss.usage().bit(), swiss.resolutionMeters());
         }
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.AHN)) {
         AhnElevationSource.Sample ahn = this.ahn.sample(blockX, blockZ, worldScale);
         if (ahn.usable()) {
            return diagnostic(ahn.elevation(), ahn.usage(), ahn.usage().bit(), ahn.resolutionMeters());
         }
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.CANELEVATION)) {
         CanElevationSource.Sample canada = this.canElevation.sample(blockX, blockZ, worldScale, previewResolutionMeters);
         if (canada.usable()) {
            return diagnostic(canada.elevation(), canada.usage(), canada.usage().bit(), canada.resolutionMeters());
         }
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.NORWAYDTM1)) {
         NorwayDtm1ElevationSource.Sample norway = this.norwayDtm1.sample(blockX, blockZ, worldScale, previewResolutionMeters);
         if (norway.usable()) {
            return diagnostic(norway.elevation(), norway.usage(), norway.usage().bit(), norway.resolutionMeters());
         }
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.JAPANGSI)) {
         JapanGsiElevationSource.Sample japan = this.japanGsi.sample(blockX, blockZ, worldScale, previewResolutionMeters);
         if (japan.usable()) {
            return diagnostic(japan.elevation(), japan.usage(), japan.usage().bit(), japan.resolutionMeters());
         }
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.ARCTICDEM)) {
         TellusElevationSource.PolarDemSample polar = this.samplePolarDem(blockX, blockZ, worldScale);
         if (polar.usable()) {
            return diagnostic(polar.elevation(), polar.usage());
         }
      }

      TellusElevationSource.AutoDecision decision = this.autoDecision(blockX, blockZ, worldScale);
      if (decision.preferUsgs()) {
         if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.USGS)) {
            TellusElevationSource.ElevationDiagnostic usgsSample = this.sampleUsgsPreferredDiagnostic(
               blockX, blockZ, worldScale, highResOcean, decision.landMaskSample()
            );
            if (!Double.isNaN(usgsSample.elevation())) {
               return usgsSample;
            }
         }

         if (demSelection.copernicusEnabled()) {
            TellusElevationSource.ElevationDiagnostic copernicusSample = this.sampleCopernicusPreferredDiagnostic(
               blockX, blockZ, worldScale, highResOcean, decision.landMaskSample(), demSelection.terrainTilesEnabled()
            );
            if (!Double.isNaN(copernicusSample.elevation())) {
               return copernicusSample;
            }
         }
      } else if (decision.preferCopernicus() && demSelection.copernicusEnabled()) {
         TellusElevationSource.ElevationDiagnostic copernicusSample = this.sampleCopernicusPreferredDiagnostic(
            blockX, blockZ, worldScale, highResOcean, decision.landMaskSample(), demSelection.terrainTilesEnabled()
         );
         if (!Double.isNaN(copernicusSample.elevation())) {
            return copernicusSample;
         }
      }

      return this.sampleFinalFallbackDiagnostic(blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters);
   }

   private double sampleFinalFallbackMeters(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      if (demSelection.terrainTilesEnabled()) {
         return this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
      }

      if (demSelection.copernicusEnabled()) {
         return this.sampleCopernicusPreferredMeters(
            blockX, blockZ, worldScale, highResOcean, this.landMask.sampleLandMask(blockX, blockZ, worldScale), false
         );
      }

      return this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
   }

   private double sampleFinalFallbackMetersLocalOnly(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      if (demSelection.terrainTilesEnabled()) {
         return this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
      }

      if (demSelection.copernicusEnabled()) {
         double copernicusSample = this.sampleCopernicusPreferredMetersLocalOnly(
            blockX,
            blockZ,
            worldScale,
            highResOcean,
            this.landMask.sampleLandMaskLocalOnly(blockX, blockZ, worldScale),
            false,
            previewResolutionMeters
         );
         if (!Double.isNaN(copernicusSample)) {
            return copernicusSample;
         }
      }

      return this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
   }

   private TellusElevationSource.ElevationDiagnostic sampleFinalFallbackDiagnostic(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      if (demSelection.terrainTilesEnabled()) {
         return this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
      }

      if (demSelection.copernicusEnabled()) {
         return this.sampleCopernicusPreferredDiagnostic(
            blockX, blockZ, worldScale, highResOcean, this.landMask.sampleLandMask(blockX, blockZ, worldScale), false
         );
      }

      return this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
   }

   public void prefetchTiles(double blockX, double blockZ, double worldScale, int radius) {
      this.prefetchTiles(blockX, blockZ, worldScale, radius, EarthGeneratorSettings.DEFAULT.demSelection());
   }

   public void prefetchTiles(
      double blockX,
      double blockZ,
      double worldScale,
      int radius,
      EarthGeneratorSettings.DemSelection demSelection
   ) {
      this.prefetchTiles(blockX, blockZ, worldScale, radius, demSelection, worldScale);
   }

   public void prefetchTiles(
      double blockX,
      double blockZ,
      double worldScale,
      int radius,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      if (!(worldScale <= 0.0)) {
         if (NORMALIZED_ENABLED) {
            this.prefetchNormalizedTiles(blockX, blockZ, worldScale, radius, demSelection, previewResolutionMeters);
            return;
         }

         this.prefetchTilesFromProviders(blockX, blockZ, worldScale, radius, demSelection, previewResolutionMeters);
      }
   }

   private void prefetchTilesFromProviders(
      double blockX,
      double blockZ,
      double worldScale,
      int radius,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      if (demSelection.isAllEnabled()) {
         this.prefetchTilesFromLegacyProvider(
            blockX, blockZ, worldScale, radius, EarthGeneratorSettings.DemProvider.AUTO, previewResolutionMeters
         );
         return;
      }

      this.prefetchFilteredTiles(blockX, blockZ, worldScale, radius, demSelection, previewResolutionMeters);
   }

   private void prefetchTilesFromLegacyProvider(
      double blockX, double blockZ, double worldScale, int radius, EarthGeneratorSettings.DemProvider demProvider, double previewResolutionMeters
   ) {
         TellusElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
         boolean manualSwissAlti3d = demProvider == EarthGeneratorSettings.DemProvider.SWISSALTI3D;
         boolean autoSwissAlti3d = demProvider == EarthGeneratorSettings.DemProvider.AUTO && this.shouldPreferSwissAlti3d(blockX, blockZ, worldScale);
         if (manualSwissAlti3d || autoSwissAlti3d) {
            this.swissAlti3d.prefetchTiles(blockX, blockZ, worldScale, radius, previewResolutionMeters);
         }

         if (manualSwissAlti3d) {
            return;
         }

         boolean manualAhn = demProvider == EarthGeneratorSettings.DemProvider.AHN;
         boolean autoAhn = !autoSwissAlti3d && demProvider == EarthGeneratorSettings.DemProvider.AUTO && this.shouldPreferAhn(blockX, blockZ, worldScale);
         if (manualAhn || autoAhn) {
            this.ahn.prefetchTiles(blockX, blockZ, worldScale, radius);
         }

         if (manualAhn) {
            return;
         }

         boolean manualCanElevation = demProvider == EarthGeneratorSettings.DemProvider.CANELEVATION;
         boolean autoCanElevation = !autoSwissAlti3d
            && !autoAhn
            && demProvider == EarthGeneratorSettings.DemProvider.AUTO
            && this.shouldPreferCanElevation(blockX, blockZ, worldScale);
         if (manualCanElevation || autoCanElevation) {
            this.canElevation.prefetchTiles(blockX, blockZ, worldScale, radius, previewResolutionMeters);
         }

         if (manualCanElevation) {
            return;
         }

         boolean manualNorwayDtm1 = demProvider == EarthGeneratorSettings.DemProvider.NORWAYDTM1;
         boolean autoNorwayDtm1 = !autoSwissAlti3d
            && !autoAhn
            && !autoCanElevation
            && demProvider == EarthGeneratorSettings.DemProvider.AUTO
            && this.shouldPreferNorwayDtm1(blockX, blockZ, worldScale);
         if (manualNorwayDtm1 || autoNorwayDtm1) {
            this.norwayDtm1.prefetchTiles(blockX, blockZ, worldScale, radius, previewResolutionMeters);
         }

         if (manualNorwayDtm1) {
            return;
         }

         boolean manualJapanGsi = demProvider == EarthGeneratorSettings.DemProvider.JAPANGSI;
         boolean autoJapanGsi = !autoSwissAlti3d
            && !autoAhn
            && !autoCanElevation
            && !autoNorwayDtm1
            && demProvider == EarthGeneratorSettings.DemProvider.AUTO
            && this.shouldPreferJapanGsi(blockX, blockZ, worldScale);
         if (manualJapanGsi || autoJapanGsi) {
            this.japanGsi.prefetchTiles(blockX, blockZ, worldScale, radius, previewResolutionMeters);
         }

         if (manualJapanGsi) {
            return;
         }

         TellusElevationSource.AutoDecision autoDecision = demProvider == EarthGeneratorSettings.DemProvider.AUTO
            ? this.autoDecision(blockX, blockZ, worldScale)
            : TellusElevationSource.AutoDecision.DO_NOT_PREFER;
         if (autoSwissAlti3d || autoAhn || autoCanElevation || autoNorwayDtm1 || autoJapanGsi) {
            autoDecision = TellusElevationSource.AutoDecision.DO_NOT_PREFER;
         }

         if (demProvider == EarthGeneratorSettings.DemProvider.ARCTICDEM || demProvider == EarthGeneratorSettings.DemProvider.AUTO) {
            if (!autoSwissAlti3d && !autoAhn && !autoCanElevation && !autoNorwayDtm1 && !autoJapanGsi) {
               this.prefetchPolarDem(blockX, blockZ, worldScale, radius);
            }
         }

         if (!autoSwissAlti3d
            && !autoAhn
            && !autoCanElevation
            && !autoNorwayDtm1
            && !autoJapanGsi
            && (demProvider == EarthGeneratorSettings.DemProvider.USGS || autoDecision.preferUsgs())) {
            this.usgs.prefetchTiles(blockX, blockZ, worldScale, radius);
         }

         if (!autoSwissAlti3d
            && !autoAhn
            && !autoCanElevation
            && !autoNorwayDtm1
            && !autoJapanGsi
            && (demProvider == EarthGeneratorSettings.DemProvider.COPERNICUS || autoDecision.preferCopernicus() && !autoDecision.preferUsgs())) {
            this.copernicus.prefetchTiles(blockX, blockZ, worldScale, radius);
         }

         boolean autoPolar = demProvider == EarthGeneratorSettings.DemProvider.AUTO && latLon != null && isPolarCoverage(latLon.lat());
         boolean skipTerrainPrefetch = switch (demProvider) {
            case SWISSALTI3D, AHN, CANELEVATION, NORWAYDTM1, JAPANGSI, ARCTICDEM, USGS, COPERNICUS -> true;
            case AUTO -> autoSwissAlti3d || autoAhn || autoCanElevation || autoNorwayDtm1 || autoJapanGsi || autoPolar || autoDecision.preferUsgs()
               || autoDecision.preferCopernicus();
            default -> false;
         };
         boolean prefetchFallbackTerrain = switch (demProvider) {
            case SWISSALTI3D, AHN, CANELEVATION, NORWAYDTM1, JAPANGSI, ARCTICDEM, USGS, COPERNICUS -> true;
            case AUTO -> autoSwissAlti3d
               || autoAhn
               || autoCanElevation
               || autoNorwayDtm1
               || autoJapanGsi
               || autoPolar
               || autoDecision.preferUsgs()
               || autoDecision.preferCopernicus();
            default -> false;
         };
         if (!skipTerrainPrefetch || prefetchFallbackTerrain) {
            this.prefetchTerrainTiles(blockX, blockZ, worldScale, radius, previewResolutionMeters);
         }
   }

   private void prefetchFilteredTiles(
      double blockX,
      double blockZ,
      double worldScale,
      int radius,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.SWISSALTI3D) && this.shouldPreferSwissAlti3d(blockX, blockZ, worldScale)) {
         this.swissAlti3d.prefetchTiles(blockX, blockZ, worldScale, radius, previewResolutionMeters);
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.AHN) && this.shouldPreferAhn(blockX, blockZ, worldScale)) {
         this.ahn.prefetchTiles(blockX, blockZ, worldScale, radius);
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.CANELEVATION) && this.shouldPreferCanElevation(blockX, blockZ, worldScale)) {
         this.canElevation.prefetchTiles(blockX, blockZ, worldScale, radius, previewResolutionMeters);
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.NORWAYDTM1) && this.shouldPreferNorwayDtm1(blockX, blockZ, worldScale)) {
         this.norwayDtm1.prefetchTiles(blockX, blockZ, worldScale, radius, previewResolutionMeters);
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.JAPANGSI) && this.shouldPreferJapanGsi(blockX, blockZ, worldScale)) {
         this.japanGsi.prefetchTiles(blockX, blockZ, worldScale, radius, previewResolutionMeters);
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.ARCTICDEM)) {
         this.prefetchPolarDem(blockX, blockZ, worldScale, radius);
      }

      TellusElevationSource.AutoDecision autoDecision = this.autoDecision(blockX, blockZ, worldScale);
      if (autoDecision.preferUsgs() && demSelection.isEnabled(EarthGeneratorSettings.DemProvider.USGS)) {
         this.usgs.prefetchTiles(blockX, blockZ, worldScale, radius);
      }

      boolean shouldPrefetchCopernicus = demSelection.copernicusEnabled()
         && (!demSelection.terrainTilesEnabled() || autoDecision.preferUsgs() || autoDecision.preferCopernicus());
      if (shouldPrefetchCopernicus) {
         this.copernicus.prefetchTiles(blockX, blockZ, worldScale, radius);
      }

      this.prefetchTerrainTiles(blockX, blockZ, worldScale, radius, previewResolutionMeters);
   }

   private void prefetchTerrainTiles(double blockX, double blockZ, double worldScale, int radius, double previewResolutionMeters) {
      int step = downsampleStep(worldScale, RESOLUTION_METERS, previewResolutionMeters);
      if (step > 1) {
         blockX = downsampleBlock(blockX, step);
         blockZ = downsampleBlock(blockZ, step);
      }

      int zoom = Mth.clamp(selectZoom(worldScale), MIN_ZOOM, LAND_MAX_ZOOM);
      TellusElevationSource.TileKey center = tileKeyForBlock(blockX, blockZ, worldScale, zoom);
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
            this.prefetchTile(new TellusElevationSource.TileKey(zoom, tileX, tileY));
         }
      }
   }

   private void prefetchNormalizedTiles(
      double blockX,
      double blockZ,
      double worldScale,
      int radius,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      double effectiveResolutionMeters = effectiveSampleResolutionMeters(worldScale, previewResolutionMeters);
      double projectedX = blockX * worldScale;
      double projectedZ = blockZ * worldScale;
      double projectedRadius = Math.max(1, radius) * TILE_SIZE * worldScale;
      NORMALIZED_CACHE.prefetchRange(
         projectedX - projectedRadius,
         projectedZ - projectedRadius,
         projectedX + projectedRadius,
         projectedZ + projectedRadius,
         effectiveResolutionMeters,
         demSelection,
         true,
         this::buildNormalizedTile
      );
   }

   private TellusElevationSource.ElevationDiagnostic sampleDiagnosticFromNormalizedCache(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      double effectiveResolutionMeters = effectiveSampleResolutionMeters(worldScale, previewResolutionMeters);
      NormalizedElevationTileSample sample = NORMALIZED_CACHE.sample(
         blockX * worldScale, blockZ * worldScale, effectiveResolutionMeters, demSelection, highResOcean, this::buildNormalizedTile
      );
      return diagnostic(sample.elevationMeters(), sample.primaryProvider(), sample.providerMask(), sample.resolutionMeters());
   }

   private NormalizedElevationTile buildNormalizedTile(NormalizedElevationTileKey key) {
      this.prefetchNormalizedIngestSources(key);
      ShortRaster heights = ShortRaster.create(NormalizedElevationTileKey.TILE_SIZE, NormalizedElevationTileKey.TILE_SIZE);
      int sampleCount = NormalizedElevationTileKey.TILE_SIZE * NormalizedElevationTileKey.TILE_SIZE;
      byte[] primaryProviders = new byte[sampleCount];
      byte[] blendedFlags = new byte[TellusElevationProvenance.bitSetLength(sampleCount)];
      int providerMask = 0;
      double sampleResolutionMeters = key.sampleResolutionMeters();

      for (int localZ = 0; localZ < NormalizedElevationTileKey.TILE_SIZE; localZ++) {
         for (int localX = 0; localX < NormalizedElevationTileKey.TILE_SIZE; localX++) {
            double projectedX = key.sampleProjectedX(localX);
            double projectedZ = key.sampleProjectedZ(localZ);
            double pseudoBlockX = projectedX / sampleResolutionMeters;
            double pseudoBlockZ = projectedZ / sampleResolutionMeters;
            TellusElevationSource.ElevationDiagnostic diagnostic = this.sampleDiagnosticFromProviders(
               pseudoBlockX, pseudoBlockZ, sampleResolutionMeters, key.highResOcean(), key.demSelection(), sampleResolutionMeters
            );
            int sampleIndex = localX + localZ * NormalizedElevationTileKey.TILE_SIZE;
            double elevationMeters = Double.isFinite(diagnostic.elevation()) ? diagnostic.elevation() : 0.0;
            int roundedElevation = Mth.clamp((int)Math.round(elevationMeters), (int)Short.MIN_VALUE, (int)Short.MAX_VALUE);
            heights.set(localX, localZ, (short)roundedElevation);
            primaryProviders[sampleIndex] = (byte)diagnostic.primaryProvider().ordinal();
            providerMask |= diagnostic.providerMask();
            if (diagnostic.usesMultipleProviders()) {
               blendedFlags[sampleIndex >> 3] = (byte)(blendedFlags[sampleIndex >> 3] | 1 << (sampleIndex & 7));
            }
         }
      }

      return new NormalizedElevationTile(
         key,
         heights,
         new TellusElevationProvenance(
            NormalizedElevationTileKey.TILE_SIZE, NormalizedElevationTileKey.TILE_SIZE, providerMask, primaryProviders, blendedFlags
         )
      );
   }

   private void prefetchNormalizedIngestSources(NormalizedElevationTileKey key) {
      double sampleResolutionMeters = key.sampleResolutionMeters();
      double centerBlockX = key.sampleProjectedX(NormalizedElevationTileKey.TILE_SIZE / 2) / sampleResolutionMeters;
      double centerBlockZ = key.sampleProjectedZ(NormalizedElevationTileKey.TILE_SIZE / 2) / sampleResolutionMeters;

      try {
         this.prefetchTilesFromProviders(centerBlockX, centerBlockZ, sampleResolutionMeters, 1, key.demSelection(), sampleResolutionMeters);
      } catch (RuntimeException error) {
         Tellus.LOGGER.debug("Failed to prefetch ingest sources for normalized elevation tile {}", key, error);
      }
   }

   private void compareNormalizedElevation(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters,
      double normalizedElevationMeters
   ) {
      if (!NORMALIZED_COMPARE) {
         return;
      }

      double providerElevationMeters = this.sampleElevationMetersFromProviders(
         blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters
      );
      if (Math.abs(providerElevationMeters - normalizedElevationMeters) > 1.0) {
         this.logNormalizedMismatch(blockX, blockZ, worldScale, demSelection, previewResolutionMeters, normalizedElevationMeters, providerElevationMeters);
      }
   }

   private void logNormalizedMismatch(
      double blockX,
      double blockZ,
      double worldScale,
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
               worldScale,
               demSelection.fingerprint(),
               previewResolutionMeters,
               normalizedElevationMeters,
               providerElevationMeters
            }
         );
      }
   }

   public static boolean usesPolarDem(EarthGeneratorSettings.DemSelection demSelection) {
      return demSelection != null && demSelection.usesPolarDem();
   }

   public static double remaBoundaryBlockZ(double worldScale) {
      return worldScale > 0.0 ? EarthProjection.latToBlockZ(POLAR_SOUTH_MAX_LAT, worldScale) : Double.POSITIVE_INFINITY;
   }

   private double sampleAutomaticMeters(double blockX, double blockZ, double worldScale, boolean highResOcean, double previewResolutionMeters) {
      return this.sampleAutomaticMeters(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters, true);
   }

   private double sampleAutomaticMeters(
      double blockX, double blockZ, double worldScale, boolean highResOcean, double previewResolutionMeters, boolean allowJapanGsi
   ) {
      SwissAlti3dElevationSource.Sample swiss = this.swissAlti3d.sample(blockX, blockZ, worldScale, previewResolutionMeters);
      if (swiss.usable()) {
         return swiss.elevation();
      }

      AhnElevationSource.Sample ahn = this.ahn.sample(blockX, blockZ, worldScale);
      if (ahn.usable()) {
         return ahn.elevation();
      }

      CanElevationSource.Sample canada = this.canElevation.sample(blockX, blockZ, worldScale, previewResolutionMeters);
      if (canada.usable()) {
         return canada.elevation();
      }

      NorwayDtm1ElevationSource.Sample norway = this.norwayDtm1.sample(blockX, blockZ, worldScale, previewResolutionMeters);
      if (norway.usable()) {
         return norway.elevation();
      }

      if (allowJapanGsi) {
         JapanGsiElevationSource.Sample japan = this.japanGsi.sample(blockX, blockZ, worldScale, previewResolutionMeters);
         if (japan.usable()) {
            return japan.elevation();
         }
      }

      TellusElevationSource.PolarDemSample polar = this.samplePolarDem(blockX, blockZ, worldScale);
      if (polar.usable()) {
         return polar.elevation();
      }

      TellusElevationSource.AutoDecision decision = this.autoDecision(blockX, blockZ, worldScale);
      if (decision.preferUsgs()) {
         double usgsSample = this.sampleUsgsMetersWithFallback(blockX, blockZ, worldScale, highResOcean, decision.landMaskSample());
         if (!Double.isNaN(usgsSample)) {
            return usgsSample;
         }
      }

      if (decision.preferTerrainTiles()) {
         return this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean);
      }

      if (decision.preferCopernicus()) {
         double copernicusSample = this.sampleCopernicusPreferredMeters(
            blockX, blockZ, worldScale, highResOcean, decision.landMaskSample(), false
         );
         if (!Double.isNaN(copernicusSample)) {
            return copernicusSample;
         }
      }

      return this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean);
   }

   private double sampleAutomaticMetersLocalOnly(
      double blockX, double blockZ, double worldScale, boolean highResOcean, double previewResolutionMeters
   ) {
      return this.sampleAutomaticMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters, true);
   }

   private double sampleAutomaticMetersLocalOnly(
      double blockX, double blockZ, double worldScale, boolean highResOcean, double previewResolutionMeters, boolean allowJapanGsi
   ) {
      SwissAlti3dElevationSource.Sample swiss = this.swissAlti3d.sampleLocalOnly(blockX, blockZ, worldScale, previewResolutionMeters);
      if (swiss.usable()) {
         return swiss.elevation();
      }

      AhnElevationSource.Sample ahn = this.ahn.sampleLocalOnly(blockX, blockZ, worldScale);
      if (ahn.usable()) {
         return ahn.elevation();
      }

      CanElevationSource.Sample canada = this.canElevation.sampleLocalOnly(blockX, blockZ, worldScale, previewResolutionMeters);
      if (canada.usable()) {
         return canada.elevation();
      }

      NorwayDtm1ElevationSource.Sample norway = this.norwayDtm1.sampleLocalOnly(blockX, blockZ, worldScale, previewResolutionMeters);
      if (norway.usable()) {
         return norway.elevation();
      }

      if (allowJapanGsi) {
         JapanGsiElevationSource.Sample japan = this.japanGsi.sampleLocalOnly(blockX, blockZ, worldScale, previewResolutionMeters);
         if (japan.usable()) {
            return japan.elevation();
         }
      }

      TellusElevationSource.PolarDemSample polar = this.samplePolarDemLocalOnly(blockX, blockZ, worldScale);
      if (polar.usable()) {
         return polar.elevation();
      }

      TellusElevationSource.AutoDecision decision = this.autoDecisionLocalOnly(blockX, blockZ, worldScale);
      if (decision.preferUsgs()) {
         double usgsSample = this.sampleUsgsMetersWithFallbackLocalOnly(
            blockX, blockZ, worldScale, highResOcean, decision.landMaskSample(), previewResolutionMeters
         );
         if (!Double.isNaN(usgsSample)) {
            return usgsSample;
         }
      }

      if (decision.preferTerrainTiles()) {
         return this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
      }

      if (decision.preferCopernicus()) {
         double copernicusSample = this.sampleCopernicusPreferredMetersLocalOnly(
            blockX, blockZ, worldScale, highResOcean, decision.landMaskSample(), false, previewResolutionMeters
         );
         if (!Double.isNaN(copernicusSample)) {
            return copernicusSample;
         }
      }

      return this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
   }

   private TellusElevationSource.ElevationDiagnostic sampleAutomaticDiagnostic(
      double blockX, double blockZ, double worldScale, boolean highResOcean, double previewResolutionMeters
   ) {
      return this.sampleAutomaticDiagnostic(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters, true);
   }

   private TellusElevationSource.ElevationDiagnostic sampleAutomaticDiagnostic(
      double blockX, double blockZ, double worldScale, boolean highResOcean, double previewResolutionMeters, boolean allowJapanGsi
   ) {
      SwissAlti3dElevationSource.Sample swiss = this.swissAlti3d.sample(blockX, blockZ, worldScale, previewResolutionMeters);
      if (swiss.usable()) {
         return diagnostic(swiss.elevation(), swiss.usage(), swiss.usage().bit(), swiss.resolutionMeters());
      }

      AhnElevationSource.Sample ahn = this.ahn.sample(blockX, blockZ, worldScale);
      if (ahn.usable()) {
         return diagnostic(ahn.elevation(), ahn.usage(), ahn.usage().bit(), ahn.resolutionMeters());
      }

      CanElevationSource.Sample canada = this.canElevation.sample(blockX, blockZ, worldScale, previewResolutionMeters);
      if (canada.usable()) {
         return diagnostic(canada.elevation(), canada.usage(), canada.usage().bit(), canada.resolutionMeters());
      }

      NorwayDtm1ElevationSource.Sample norway = this.norwayDtm1.sample(blockX, blockZ, worldScale, previewResolutionMeters);
      if (norway.usable()) {
         return diagnostic(norway.elevation(), norway.usage(), norway.usage().bit(), norway.resolutionMeters());
      }

      if (allowJapanGsi) {
         JapanGsiElevationSource.Sample japan = this.japanGsi.sample(blockX, blockZ, worldScale, previewResolutionMeters);
         if (japan.usable()) {
            return diagnostic(japan.elevation(), japan.usage(), japan.usage().bit(), japan.resolutionMeters());
         }
      }

      TellusElevationSource.PolarDemSample polar = this.samplePolarDem(blockX, blockZ, worldScale);
      if (polar.usable()) {
         return diagnostic(polar.elevation(), polar.usage());
      }

      TellusElevationSource.AutoDecision decision = this.autoDecision(blockX, blockZ, worldScale);
      if (decision.preferUsgs()) {
         TellusElevationSource.ElevationDiagnostic usgsSample = this.sampleUsgsMetersWithFallbackDiagnostic(
            blockX, blockZ, worldScale, highResOcean, decision.landMaskSample()
         );
         if (!Double.isNaN(usgsSample.elevation())) {
            return usgsSample;
         }
      }

      if (decision.preferTerrainTiles()) {
         return this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean);
      }

      if (decision.preferCopernicus()) {
         TellusElevationSource.ElevationDiagnostic copernicusSample = this.sampleCopernicusPreferredDiagnostic(
            blockX, blockZ, worldScale, highResOcean, decision.landMaskSample(), false
         );
         if (!Double.isNaN(copernicusSample.elevation())) {
            return copernicusSample;
         }
      }

      return this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean);
   }

   private boolean shouldPreferSwissAlti3d(double blockX, double blockZ, double worldScale) {
      TellusElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
      return latLon != null && this.swissAlti3d.isLikelyInCoverage(latLon.lat(), latLon.lon());
   }

   private boolean shouldPreferAhn(double blockX, double blockZ, double worldScale) {
      TellusElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
      return latLon != null && this.ahn.isLikelyInCoverage(latLon.lat(), latLon.lon());
   }

   private boolean shouldPreferCanElevation(double blockX, double blockZ, double worldScale) {
      TellusElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
      return latLon != null && this.canElevation.isLikelyInCoverage(latLon.lat(), latLon.lon());
   }

   private boolean shouldPreferNorwayDtm1(double blockX, double blockZ, double worldScale) {
      TellusElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
      return latLon != null && this.norwayDtm1.isLikelyInCoverage(latLon.lat(), latLon.lon());
   }

   private boolean shouldPreferJapanGsi(double blockX, double blockZ, double worldScale) {
      TellusElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
      return latLon != null && this.japanGsi.isLikelyInCoverage(latLon.lat(), latLon.lon());
   }

   private boolean shouldPreferTerrainTilesOverCopernicus(double lat, double lon) {
      double terrainResolutionMeters = this.terrainResolutionIndex.lookupResolutionMeters(lat, lon);
      return Double.isFinite(terrainResolutionMeters) && terrainResolutionMeters > 0.0 && terrainResolutionMeters < RESOLUTION_METERS;
   }

   private TellusElevationSource.PolarDemSample samplePolarDem(double blockX, double blockZ, double worldScale) {
      TellusElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
      if (latLon == null) {
         return TellusElevationSource.PolarDemSample.none();
      } else if (latLon.lat() >= POLAR_NORTH_MIN_LAT) {
         double arctic = this.arcticDem.sampleElevationMeters(blockX, blockZ, worldScale);
         return !Double.isNaN(arctic)
            ? new TellusElevationSource.PolarDemSample(arctic, TellusElevationSource.DemUsage.ARCTICDEM)
            : TellusElevationSource.PolarDemSample.none();
      } else if (latLon.lat() <= POLAR_SOUTH_MAX_LAT) {
         double rema = this.rema.sampleElevationMeters(blockX, blockZ, worldScale);
         return !Double.isNaN(rema)
            ? new TellusElevationSource.PolarDemSample(rema, TellusElevationSource.DemUsage.REMA)
            : TellusElevationSource.PolarDemSample.none();
      } else {
         return TellusElevationSource.PolarDemSample.none();
      }
   }

   private TellusElevationSource.PolarDemSample samplePolarDemLocalOnly(double blockX, double blockZ, double worldScale) {
      TellusElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
      if (latLon == null) {
         return TellusElevationSource.PolarDemSample.none();
      } else if (latLon.lat() >= POLAR_NORTH_MIN_LAT) {
         double arctic = this.arcticDem.sampleElevationMetersLocalOnly(blockX, blockZ, worldScale);
         return !Double.isNaN(arctic)
            ? new TellusElevationSource.PolarDemSample(arctic, TellusElevationSource.DemUsage.ARCTICDEM)
            : TellusElevationSource.PolarDemSample.none();
      } else if (latLon.lat() <= POLAR_SOUTH_MAX_LAT) {
         double rema = this.rema.sampleElevationMetersLocalOnly(blockX, blockZ, worldScale);
         return !Double.isNaN(rema)
            ? new TellusElevationSource.PolarDemSample(rema, TellusElevationSource.DemUsage.REMA)
            : TellusElevationSource.PolarDemSample.none();
      } else {
         return TellusElevationSource.PolarDemSample.none();
      }
   }

   private void prefetchPolarDem(double blockX, double blockZ, double worldScale, int radius) {
      TellusElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
      if (latLon != null) {
         if (latLon.lat() >= POLAR_NORTH_MIN_LAT) {
            this.arcticDem.prefetchTiles(blockX, blockZ, worldScale, radius);
         } else if (latLon.lat() <= POLAR_SOUTH_MAX_LAT) {
            this.rema.prefetchTiles(blockX, blockZ, worldScale, radius);
         }
      }
   }

   private TellusElevationSource.ElevationDiagnostic sampleUsgsMetersWithFallbackDiagnostic(
      double blockX, double blockZ, double worldScale, boolean highResOcean
   ) {
      TellusLandMaskSource.LandMaskSample landMaskSample = this.landMask.sampleLandMask(blockX, blockZ, worldScale);
      return this.sampleUsgsMetersWithFallbackDiagnostic(blockX, blockZ, worldScale, highResOcean, landMaskSample);
   }

   private double sampleUsgsMetersWithFallback(double blockX, double blockZ, double worldScale, boolean highResOcean) {
      TellusLandMaskSource.LandMaskSample landMaskSample = this.landMask.sampleLandMask(blockX, blockZ, worldScale);
      return this.sampleUsgsMetersWithFallback(blockX, blockZ, worldScale, highResOcean, landMaskSample);
   }

   private double sampleUsgsMetersWithFallbackLocalOnly(
      double blockX, double blockZ, double worldScale, boolean highResOcean, double previewResolutionMeters
   ) {
      TellusLandMaskSource.LandMaskSample landMaskSample = this.landMask.sampleLandMaskLocalOnly(blockX, blockZ, worldScale);
      return this.sampleUsgsMetersWithFallbackLocalOnly(blockX, blockZ, worldScale, highResOcean, landMaskSample, previewResolutionMeters);
   }

   private double sampleUsgsMetersWithFallback(
      double blockX, double blockZ, double worldScale, boolean highResOcean, TellusLandMaskSource.LandMaskSample landMaskSample
   ) {
      double sample = this.sampleUsgsPreferredMeters(blockX, blockZ, worldScale, highResOcean, landMaskSample);
      return !Double.isNaN(sample)
         ? sample
         : this.sampleCopernicusPreferredMeters(blockX, blockZ, worldScale, highResOcean, landMaskSample, true);
   }

   private double sampleUsgsMetersWithFallbackLocalOnly(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      TellusLandMaskSource.LandMaskSample landMaskSample,
      double previewResolutionMeters
   ) {
      double sample = this.sampleUsgsPreferredMetersLocalOnly(
         blockX, blockZ, worldScale, highResOcean, landMaskSample, previewResolutionMeters
      );
      return !Double.isNaN(sample)
         ? sample
         : this.sampleCopernicusPreferredMetersLocalOnly(
            blockX, blockZ, worldScale, highResOcean, landMaskSample, true, previewResolutionMeters
         );
   }

   private TellusElevationSource.ElevationDiagnostic sampleUsgsMetersWithFallbackDiagnostic(
      double blockX, double blockZ, double worldScale, boolean highResOcean, TellusLandMaskSource.LandMaskSample landMaskSample
   ) {
      TellusElevationSource.ElevationDiagnostic sample = this.sampleUsgsPreferredDiagnostic(blockX, blockZ, worldScale, highResOcean, landMaskSample);
      return !Double.isNaN(sample.elevation())
         ? sample
         : this.sampleCopernicusPreferredDiagnostic(blockX, blockZ, worldScale, highResOcean, landMaskSample, true);
   }

   private double sampleUsgsPreferredMeters(
      double blockX, double blockZ, double worldScale, boolean highResOcean, TellusLandMaskSource.LandMaskSample landMaskSample
   ) {
      if (landMaskSample.known() && !landMaskSample.land()) {
         return this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean);
      } else {
         double sample = this.usgs.sampleElevationMeters(blockX, blockZ, worldScale);
         if (Double.isNaN(sample)) {
            return Double.NaN;
         } else if (sample <= 0.0 && highResOcean && !landMaskSample.known()) {
            return this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean);
         } else {
            return sample;
         }
      }
   }

   private double sampleUsgsPreferredMetersLocalOnly(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      TellusLandMaskSource.LandMaskSample landMaskSample,
      double previewResolutionMeters
   ) {
      if (landMaskSample.known() && !landMaskSample.land()) {
         return this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
      } else {
         double sample = this.usgs.sampleElevationMetersLocalOnly(blockX, blockZ, worldScale);
         if (Double.isNaN(sample)) {
            return Double.NaN;
         } else if (sample <= 0.0 && highResOcean && !landMaskSample.known()) {
            return this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
         } else {
            return sample;
         }
      }
   }

   private TellusElevationSource.ElevationDiagnostic sampleUsgsPreferredDiagnostic(
      double blockX, double blockZ, double worldScale, boolean highResOcean, TellusLandMaskSource.LandMaskSample landMaskSample
   ) {
      if (landMaskSample.known() && !landMaskSample.land()) {
         return this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean);
      } else {
         double sample = this.usgs.sampleElevationMeters(blockX, blockZ, worldScale);
         if (Double.isNaN(sample)) {
            return diagnostic(Double.NaN, TellusElevationSource.DemUsage.USGS);
         } else if (sample <= 0.0 && highResOcean && !landMaskSample.known()) {
            return this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean);
         } else {
            return diagnostic(sample, TellusElevationSource.DemUsage.USGS);
         }
      }
   }

   private double sampleCopernicusPreferredMeters(double blockX, double blockZ, double worldScale, boolean highResOcean) {
      return this.sampleCopernicusPreferredMeters(
         blockX, blockZ, worldScale, highResOcean, this.landMask.sampleLandMask(blockX, blockZ, worldScale), true
      );
   }

   private double sampleCopernicusPreferredMetersLocalOnly(
      double blockX, double blockZ, double worldScale, boolean highResOcean, double previewResolutionMeters
   ) {
      return this.sampleCopernicusPreferredMetersLocalOnly(
         blockX, blockZ, worldScale, highResOcean, this.landMask.sampleLandMaskLocalOnly(blockX, blockZ, worldScale), true, previewResolutionMeters
      );
   }

   private TellusElevationSource.ElevationDiagnostic sampleCopernicusPreferredDiagnostic(
      double blockX, double blockZ, double worldScale, boolean highResOcean
   ) {
      return this.sampleCopernicusPreferredDiagnostic(
         blockX, blockZ, worldScale, highResOcean, this.landMask.sampleLandMask(blockX, blockZ, worldScale), true
      );
   }

   private double sampleCopernicusPreferredMeters(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      TellusLandMaskSource.LandMaskSample landMaskSample,
      boolean terrainLandFallback
   ) {
      if (landMaskSample.known() && !landMaskSample.land()) {
         return this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean);
      } else {
         double sample = this.copernicus.sampleElevationMeters(blockX, blockZ, worldScale);
         if (Double.isNaN(sample)) {
            return terrainLandFallback ? this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean) : Double.NaN;
         } else if (sample <= 0.0 && highResOcean && !landMaskSample.known()) {
            return this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean);
         } else {
            return sample;
         }
      }
   }

   private double sampleCopernicusPreferredMetersLocalOnly(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      TellusLandMaskSource.LandMaskSample landMaskSample,
      boolean terrainLandFallback,
      double previewResolutionMeters
   ) {
      if (landMaskSample.known() && !landMaskSample.land()) {
         return this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
      } else {
         double sample = this.copernicus.sampleElevationMetersLocalOnly(blockX, blockZ, worldScale);
         if (Double.isNaN(sample)) {
            return terrainLandFallback ? this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters) : Double.NaN;
         } else if (sample <= 0.0 && highResOcean && !landMaskSample.known()) {
            return this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
         } else {
            return sample;
         }
      }
   }

   private TellusElevationSource.ElevationDiagnostic sampleCopernicusPreferredDiagnostic(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      TellusLandMaskSource.LandMaskSample landMaskSample,
      boolean terrainLandFallback
   ) {
      if (landMaskSample.known() && !landMaskSample.land()) {
         return this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean);
      } else {
         double sample = this.copernicus.sampleElevationMeters(blockX, blockZ, worldScale);
         if (Double.isNaN(sample)) {
            return terrainLandFallback
               ? this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean)
               : diagnostic(Double.NaN, TellusElevationSource.DemUsage.COPERNICUS);
         } else if (sample <= 0.0 && highResOcean && !landMaskSample.known()) {
            return this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean);
         } else {
            return diagnostic(sample, TellusElevationSource.DemUsage.COPERNICUS);
         }
      }
   }

   private TellusElevationSource.AutoDecision autoDecision(double blockX, double blockZ, double worldScale) {
      TellusElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
      if (latLon == null) {
         return TellusElevationSource.AutoDecision.DO_NOT_PREFER;
      } else {
         boolean inUsgsRegion = isUsgsPreferredRegion(latLon.lat(), latLon.lon());
         boolean preferTerrainTiles = this.shouldPreferTerrainTilesOverCopernicus(latLon.lat(), latLon.lon());
         boolean preferUsgs = inUsgsRegion;
         boolean preferCopernicus = !preferUsgs && !preferTerrainTiles;

         TellusLandMaskSource.LandMaskSample landMaskSample = this.landMask.sampleLandMask(blockX, blockZ, worldScale);
         return !landMaskSample.known() || landMaskSample.land()
            ? new TellusElevationSource.AutoDecision(preferUsgs, preferTerrainTiles, preferCopernicus, landMaskSample)
            : TellusElevationSource.AutoDecision.DO_NOT_PREFER;
      }
   }

   private TellusElevationSource.AutoDecision autoDecisionLocalOnly(double blockX, double blockZ, double worldScale) {
      TellusElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
      if (latLon == null) {
         return TellusElevationSource.AutoDecision.DO_NOT_PREFER;
      } else {
         boolean inUsgsRegion = isUsgsPreferredRegion(latLon.lat(), latLon.lon());
         boolean preferTerrainTiles = this.shouldPreferTerrainTilesOverCopernicus(latLon.lat(), latLon.lon());
         boolean preferUsgs = inUsgsRegion;
         boolean preferCopernicus = !preferUsgs && !preferTerrainTiles;

         TellusLandMaskSource.LandMaskSample landMaskSample = this.landMask.sampleLandMaskLocalOnly(blockX, blockZ, worldScale);
         return !landMaskSample.known() || landMaskSample.land()
            ? new TellusElevationSource.AutoDecision(preferUsgs, preferTerrainTiles, preferCopernicus, landMaskSample)
            : TellusElevationSource.AutoDecision.DO_NOT_PREFER;
      }
   }

   private static boolean isUsgsPreferredRegion(double lat, double lon) {
      return isContiguousUsRegion(lat, lon) || isAlaskaRegion(lat, lon) || isHawaiiRegion(lat, lon) || isPuertoRicoRegion(lat, lon);
   }

   private static boolean isContiguousUsRegion(double lat, double lon) {
      return lat >= 24.0 && lat <= 49.8 && lon >= -125.0 && lon <= -66.0;
   }

   private static boolean isAlaskaRegion(double lat, double lon) {
      return lat >= 51.0 && lat <= 72.8 && lon >= -170.5 && lon <= -129.0;
   }

   private static boolean isHawaiiRegion(double lat, double lon) {
      return lat >= 18.5 && lat <= 22.7 && lon >= -160.8 && lon <= -154.2;
   }

   private static boolean isPuertoRicoRegion(double lat, double lon) {
      return lat >= 17.7 && lat <= 18.7 && lon >= -67.5 && lon <= -65.0;
   }

   private static boolean isPolarCoverage(double lat) {
      return lat >= POLAR_NORTH_MIN_LAT || lat <= POLAR_SOUTH_MAX_LAT;
   }

   private double sampleTerrariumMeters(double blockX, double blockZ, double worldScale, boolean highResOcean) {
      return this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean, worldScale);
   }

   private double sampleTerrariumMetersLocalOnly(double blockX, double blockZ, double worldScale, boolean highResOcean, double previewResolutionMeters) {
      int step = downsampleStep(worldScale, RESOLUTION_METERS, previewResolutionMeters);
      if (step > 1) {
         blockX = downsampleBlock(blockX, step);
         blockZ = downsampleBlock(blockZ, step);
      }

      int zoom = Mth.clamp(selectZoom(worldScale), MIN_ZOOM, LAND_MAX_ZOOM);
      double sample = this.sampleAtZoomLocalOnly(blockX, blockZ, worldScale, zoom);
      if (!Double.isNaN(sample)) {
         if (sample <= 0.0 && highResOcean) {
            double oceanSample = this.sampleAtZoomLocalOnly(blockX, blockZ, worldScale, OCEAN_MAX_ZOOM);
            if (!Double.isNaN(oceanSample)) {
               return oceanSample;
            }
         }

         return sample;
      } else {
         double oceanSample = this.sampleAtZoomLocalOnly(blockX, blockZ, worldScale, OCEAN_MAX_ZOOM);
         return !Double.isNaN(oceanSample) ? oceanSample : 0.0;
      }
   }

   private double sampleTerrariumMetersMemoryOnly(double blockX, double blockZ, double worldScale, boolean highResOcean, double previewResolutionMeters) {
      int step = downsampleStep(worldScale, RESOLUTION_METERS, previewResolutionMeters);
      if (step > 1) {
         blockX = downsampleBlock(blockX, step);
         blockZ = downsampleBlock(blockZ, step);
      }

      int zoom = Mth.clamp(selectZoom(worldScale), MIN_ZOOM, LAND_MAX_ZOOM);
      double sample = this.sampleAtZoomMemoryOnly(blockX, blockZ, worldScale, zoom);
      if (!Double.isNaN(sample)) {
         if (sample <= 0.0 && highResOcean) {
            double oceanSample = this.sampleAtZoomMemoryOnly(blockX, blockZ, worldScale, OCEAN_MAX_ZOOM);
            if (!Double.isNaN(oceanSample)) {
               return oceanSample;
            }
         }

         return sample;
      } else {
         double oceanSample = this.sampleAtZoomMemoryOnly(blockX, blockZ, worldScale, OCEAN_MAX_ZOOM);
         return oceanSample;
      }
   }

   private TellusElevationSource.ElevationDiagnostic terrainTilesDiagnostic(
      double blockX, double blockZ, double worldScale, boolean highResOcean
   ) {
      return this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean, worldScale);
   }

   private TellusElevationSource.ElevationDiagnostic terrainTilesDiagnostic(
      double blockX, double blockZ, double worldScale, boolean highResOcean, double previewResolutionMeters
   ) {
      return diagnostic(
         this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters),
         TellusElevationSource.DemUsage.TERRAIN_TILES,
         TellusElevationSource.DemUsage.TERRAIN_TILES.bit(),
         this.terrainTilesResolutionMeters(blockX, blockZ, worldScale)
      );
   }

   private double terrainTilesResolutionMeters(double blockX, double blockZ, double worldScale) {
      TellusElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
      if (latLon == null) {
         return TellusElevationSource.DemUsage.TERRAIN_TILES.nominalResolutionMeters();
      } else {
         double resolutionMeters = this.terrainResolutionIndex.lookupResolutionMeters(latLon.lat(), latLon.lon());
         return Double.isFinite(resolutionMeters) && resolutionMeters > 0.0
            ? resolutionMeters
            : TellusElevationSource.DemUsage.TERRAIN_TILES.nominalResolutionMeters();
      }
   }

   private double sampleTerrariumMeters(double blockX, double blockZ, double worldScale, boolean highResOcean, double previewResolutionMeters) {
      int step = downsampleStep(worldScale, RESOLUTION_METERS, previewResolutionMeters);
      if (step > 1) {
         blockX = downsampleBlock(blockX, step);
         blockZ = downsampleBlock(blockZ, step);
      }

      int zoom = Mth.clamp(selectZoom(worldScale), MIN_ZOOM, LAND_MAX_ZOOM);
      double sample = this.sampleAtZoom(blockX, blockZ, worldScale, zoom);
      if (!Double.isNaN(sample)) {
         if (sample <= 0.0 && highResOcean) {
            double oceanSample = this.sampleAtZoom(blockX, blockZ, worldScale, OCEAN_MAX_ZOOM);
            if (!Double.isNaN(oceanSample)) {
               return oceanSample;
            }
         }

         return sample;
      } else {
         double oceanSample = this.sampleAtZoom(blockX, blockZ, worldScale, OCEAN_MAX_ZOOM);
         return !Double.isNaN(oceanSample) ? oceanSample : 0.0;
      }
   }

   private double sampleAtZoom(double blockX, double blockZ, double worldScale, int zoom) {
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      double lon = blockX / blocksPerDegree;
      double lat = EarthProjection.blockZToLat(blockZ, worldScale);
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

   private double sampleAtZoomLocalOnly(double blockX, double blockZ, double worldScale, int zoom) {
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      double lon = blockX / blocksPerDegree;
      double lat = EarthProjection.blockZToLat(blockZ, worldScale);
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

   private double sampleAtZoomMemoryOnly(double blockX, double blockZ, double worldScale, int zoom) {
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      double lon = blockX / blocksPerDegree;
      double lat = EarthProjection.blockZToLat(blockZ, worldScale);
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

   private static int downsampleStep(double worldScale, double resolutionMeters, double previewResolutionMeters) {
      if (!(worldScale > 0.0)) {
         return 1;
      } else if (!(effectiveSampleResolutionMeters(worldScale, previewResolutionMeters) >= resolutionMeters)) {
         return 1;
      } else {
         return Math.max(1, Mth.floor(resolutionMeters / worldScale));
      }
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

   private static TellusElevationSource.TileKey tileKeyForBlock(double blockX, double blockZ, double worldScale, int zoom) {
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      double lon = blockX / blocksPerDegree;
      double lat = EarthProjection.blockZToLat(blockZ, worldScale);
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

   private static TellusElevationSource.LatLon toLatLon(double blockX, double blockZ, double worldScale) {
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      double lon = blockX / blocksPerDegree;
      double lat = EarthProjection.blockZToLat(blockZ, worldScale);
      return !(lat < MIN_LAT) && !(lat > MAX_LAT) && !(lon < MIN_LON) && !(lon > MAX_LON) ? new TellusElevationSource.LatLon(lat, lon) : null;
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

   private ShortRaster getTileLocalOnly(TellusElevationSource.TileKey key) {
      ShortRaster cached = (ShortRaster)this.cache.getIfPresent(key);
      if (cached != null) {
         return cached == MISSING_RASTER ? null : cached;
      } else {
         Path cachePath = this.cachePath(key);
         if (!Files.exists(cachePath)) {
            return null;
         } else {
            try (InputStream input = Files.newInputStream(cachePath)) {
               ShortRaster raster = readPngRaster(input);
               this.cache.put(key, raster);
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

   private ShortRaster loadTile( TellusElevationSource.TileKey key) {
      Path cachePath = this.cachePath(key);
      if (Files.exists(cachePath)) {
         try {
            ShortRaster var15;
            try (InputStream input = Files.newInputStream(cachePath)) {
               var15 = readPngRaster(input);
            }

            return var15;
         } catch (IOException var13) {
            this.handleInvalidTile(cachePath, key, var13);
         }
      }

      byte[] data;
      try {
         data = this.downloadTile(key);
      } catch (IOException var10) {
         Tellus.LOGGER.debug("Failed to download elevation tile {}", key, var10);
         return MISSING_RASTER;
      }

      if (data == null) {
         return MISSING_RASTER;
      } else {
         this.cacheTile(cachePath, data);

         try {
            ShortRaster var5;
            try (InputStream input = new ByteArrayInputStream(data)) {
               var5 = readPngRaster(input);
            }

            return var5;
         } catch (IOException var9) {
            this.handleInvalidTile(cachePath, key, var9);
            return MISSING_RASTER;
         }
      }
   }

   private byte[] downloadTile(TellusElevationSource.TileKey key) throws IOException {
      URI uri = URI.create(String.format("%s/%d/%d/%d.png", ENDPOINT, key.zoom(), key.x(), key.y()));
      HttpURLConnection connection = (HttpURLConnection)uri.toURL().openConnection();
      try {
         connection.setConnectTimeout(8000);
         connection.setReadTimeout(8000);
         connection.setRequestProperty("User-Agent", "Tellus/1.0 (Minecraft Mod)");
         if (connection.getResponseCode() == 404) {
            return null;
         } else {
            DownloadProgressReporter.requestStarted(connection.getContentLengthLong());

            byte[] var5;
            try (InputStream input = Objects.requireNonNull(connection.getInputStream(), "elevationTileResponse")) {
               var5 = DownloadProgressReporter.readAllBytesWithProgress(input);
            } finally {
               DownloadProgressReporter.requestFinished();
            }

            return var5;
         }
      } finally {
         connection.disconnect();
      }
   }

   private void cacheTile(Path cachePath, byte[] data) {
      try {
         Files.createDirectories(cachePath.getParent());
         Files.write(cachePath, data);
      } catch (IOException var4) {
         Tellus.LOGGER.warn("Failed to cache elevation tile {}", cachePath, var4);
      }
   }

   private Path cachePath(TellusElevationSource.TileKey key) {
      return this.cacheRoot.resolve(key.zoom() + "/" + key.x() + "/" + key.y() + ".png");
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

   private static TellusElevationSource.ElevationDiagnostic diagnostic(double elevation, TellusElevationSource.DemUsage provider) {
      return new TellusElevationSource.ElevationDiagnostic(elevation, provider, provider.bit(), provider.nominalResolutionMeters());
   }

   private static TellusElevationSource.ElevationDiagnostic diagnostic(
      double elevation, TellusElevationSource.DemUsage primaryProvider, int providerMask, double sourceResolutionMeters
   ) {
      double resolvedResolution = Double.isFinite(sourceResolutionMeters) && sourceResolutionMeters > 0.0
         ? sourceResolutionMeters
         : primaryProvider.nominalResolutionMeters();
      return new TellusElevationSource.ElevationDiagnostic(elevation, primaryProvider, providerMask, resolvedResolution);
   }

   private static double zoomForScale(double meters) {
      return Math.log(EQUATOR_CIRCUMFERENCE / (TILE_SIZE * meters)) / Math.log(2.0);
   }

   private static ShortRaster readPngRaster(InputStream input) throws IOException {
      BufferedImage image = ImageIO.read(input);
      if (image == null) {
         throw new IOException("Invalid tellus PNG tile");
      } else {
         int width = image.getWidth();
         int height = image.getHeight();
         ShortRaster raster = ShortRaster.create(width, height);

         for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
               int argb = image.getRGB(x, y);
               int red = argb >> 16 & 0xFF;
               int green = argb >> 8 & 0xFF;
               int blue = argb & 0xFF;
               double elevation = red * TILE_SIZE + green + blue / (double)TILE_SIZE - 32768.0;
               raster.set(x, y, (short)Math.round(elevation));
            }
         }

         return raster;
      }
   }

   @Override
   public TellusCacheDomain cacheDomain() {
      return TellusCacheDomain.TERRAIN;
   }

   @Override
   public void clearCache() {
      this.cache.invalidateAll();
      this.cache.cleanUp();
   }

   private record TileKey(int zoom, int x, int y) {
   }

   private record LatLon(double lat, double lon) {
   }

   private record PolarDemSample(double elevation, TellusElevationSource.DemUsage usage) {
      private static TellusElevationSource.PolarDemSample none() {
         return new TellusElevationSource.PolarDemSample(Double.NaN, null);
      }

      private boolean usable() {
         return this.usage != null && !Double.isNaN(this.elevation);
      }
   }

   public static enum DemUsage {
      TERRAIN_TILES("terrarium", 1),
      SWISSALTI3D_05M("swissalti3d_05m", 1 << 1),
      SWISSALTI3D_2M("swissalti3d_2m", 1 << 2),
      AHN("ahn", 1 << 3),
      CANELEVATION_2M("canelevation_2m", 1 << 4),
      CANELEVATION_30M("canelevation_30m", 1 << 5),
      NORWAYDTM1("norwaydtm1", 1 << 6),
      USGS("usgs", 1 << 7),
      COPERNICUS("copernicus", 1 << 8),
      HMA("hma", 1 << 9),
      ARCTICDEM("arcticdem", 1 << 10),
      REMA("rema", 1 << 11),
      JAPANGSI("japangsi", 1 << 12);

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
            case SWISSALTI3D_05M -> 0.5;
            case SWISSALTI3D_2M -> 2.0;
            case AHN -> 0.5;
            case CANELEVATION_2M -> 2.0;
            case CANELEVATION_30M -> 30.0;
            case NORWAYDTM1 -> 1.0;
            case USGS -> 10.0;
            case COPERNICUS -> 30.0;
            case HMA -> 8.0;
            case ARCTICDEM -> 2.0;
            case REMA -> 8.0;
            case JAPANGSI -> Double.NaN;
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

   private record AutoDecision(
      boolean preferUsgs,
      boolean preferTerrainTiles,
      boolean preferCopernicus,
      TellusLandMaskSource.LandMaskSample landMaskSample
   ) {
      private static final TellusElevationSource.AutoDecision DO_NOT_PREFER = new TellusElevationSource.AutoDecision(
         false, false, false, TellusLandMaskSource.LandMaskSample.unknown()
      );
   }
}
