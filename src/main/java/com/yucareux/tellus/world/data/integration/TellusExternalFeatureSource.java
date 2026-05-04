package com.yucareux.tellus.world.data.integration;

import com.yucareux.tellus.world.data.osm.OsmBuildingFeature;
import com.yucareux.tellus.world.data.osm.RoadFeature;
import com.yucareux.tellus.worldgen.EarthProjection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TellusExternalFeatureSource {
   public static final String PATH_PROPERTY = "tellus.external.features.path";
   public static final String PREFER_EXTERNAL_PROPERTY = "tellus.external.features.prefer";
   public static final String DEFAULT_RELATIVE_PATH = "tellus/external-features.json";
   private static final Logger LOGGER = LoggerFactory.getLogger("tellus");
   private static final long REFRESH_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(2L);

   private final Path path;
   private final PbfExternalFeatureSource pbfSource;
   private final OverpassExternalFeatureSource overpassSource;
   private final boolean preferExternalFeatures;
   private final Object refreshLock = new Object();
   private volatile JsonExternalFeatureSource source = new JsonExternalFeatureSource(List.of(), List.of());
   private volatile FileState fileState = FileState.missing();
   private volatile long nextRefreshAtNanos;

   public TellusExternalFeatureSource(Path path) {
      this(
         path,
         PbfExternalFeatureSource.createDefault(),
         OverpassExternalFeatureSource.createDefault(),
         Boolean.parseBoolean(System.getProperty(PREFER_EXTERNAL_PROPERTY, "true"))
      );
   }

   public TellusExternalFeatureSource(Path path, OverpassExternalFeatureSource overpassSource) {
      this(path, PbfExternalFeatureSource.disabled(), overpassSource, false);
   }

   public TellusExternalFeatureSource(Path path, OverpassExternalFeatureSource overpassSource, boolean preferExternalFeatures) {
      this(path, PbfExternalFeatureSource.disabled(), overpassSource, preferExternalFeatures);
   }

   public TellusExternalFeatureSource(
      Path path, PbfExternalFeatureSource pbfSource, OverpassExternalFeatureSource overpassSource, boolean preferExternalFeatures
   ) {
      this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
      this.pbfSource = Objects.requireNonNull(pbfSource, "pbfSource");
      this.overpassSource = Objects.requireNonNull(overpassSource, "overpassSource");
      this.preferExternalFeatures = preferExternalFeatures;
   }

   public static TellusExternalFeatureSource createDefault() {
      String configuredPath = System.getProperty(PATH_PROPERTY);
      Path path = configuredPath == null || configuredPath.isBlank()
         ? FabricLoader.getInstance().getGameDir().resolve(DEFAULT_RELATIVE_PATH)
         : Path.of(configuredPath.trim());
      return new TellusExternalFeatureSource(path);
   }

   public Path path() {
      return this.path;
   }

   public boolean available() {
      JsonExternalFeatureSource current = this.currentSource();
      return !current.roads().isEmpty()
         || !current.buildings().isEmpty()
         || !current.areas().isEmpty()
         || !current.lines().isEmpty()
         || !current.points().isEmpty()
         || this.pbfSource.available()
         || this.overpassSource.available();
   }

   public boolean roadsAvailable() {
      return !this.currentSource().roads().isEmpty() || this.pbfSource.roadsAvailable() || this.overpassSource.available();
   }

   public boolean buildingsAvailable() {
      return !this.currentSource().buildings().isEmpty() || this.pbfSource.buildingsAvailable() || this.overpassSource.available();
   }

   public boolean cityDetailsAvailable() {
      JsonExternalFeatureSource current = this.currentSource();
      return !current.areas().isEmpty()
         || !current.lines().isEmpty()
         || !current.points().isEmpty()
         || this.pbfSource.cityDetailsAvailable()
         || this.overpassSource.available();
   }

   public boolean preferExternalRoads() {
      return this.preferExternalFeatures && this.roadsAvailable();
   }

   public boolean preferExternalBuildings() {
      return this.preferExternalFeatures && this.buildingsAvailable();
   }

   public List<RoadFeature> roadsForArea(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, double worldScale, int marginBlocks) {
      GeoBounds bounds = blockBounds(minBlockX, minBlockZ, maxBlockX, maxBlockZ, worldScale, marginBlocks);
      if (bounds == null) {
         return List.of();
      }

      try {
         List<ExternalRoadFeature> externalRoads = new ArrayList<>();
         externalRoads.addAll(this.currentSource().roadsForBounds(bounds));
         externalRoads.addAll(this.pbfSource.roadsForBounds(bounds));
         if (!this.pbfSource.coversBounds(bounds)) {
            externalRoads.addAll(this.overpassSource.roadsForBounds(bounds));
         }
         if (externalRoads.isEmpty()) {
            return List.of();
         }
         List<RoadFeature> roads = new ArrayList<>(externalRoads.size());
         for (ExternalRoadFeature road : externalRoads) {
            roads.add(ExternalFeatureAdapters.toTellusRoad(road));
         }
         return List.copyOf(roads);
      } catch (RuntimeException error) {
         LOGGER.warn("Failed to query external Tellus roads from {}", this.path, error);
         return List.of();
      }
   }

   public List<OsmBuildingFeature> buildingsForArea(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, double worldScale, int marginBlocks
   ) {
      GeoBounds bounds = blockBounds(minBlockX, minBlockZ, maxBlockX, maxBlockZ, worldScale, marginBlocks);
      if (bounds == null) {
         return List.of();
      }

      try {
         List<ExternalBuildingFeature> externalBuildings = new ArrayList<>();
         externalBuildings.addAll(this.currentSource().buildingsForBounds(bounds));
         externalBuildings.addAll(this.pbfSource.buildingsForBounds(bounds));
         if (!this.pbfSource.coversBounds(bounds)) {
            externalBuildings.addAll(this.overpassSource.buildingsForBounds(bounds));
         }
         if (externalBuildings.isEmpty()) {
            return List.of();
         }
         List<OsmBuildingFeature> buildings = new ArrayList<>(externalBuildings.size());
         for (ExternalBuildingFeature building : externalBuildings) {
            buildings.add(ExternalFeatureAdapters.toTellusBuilding(building));
         }
         return List.copyOf(buildings);
      } catch (RuntimeException error) {
         LOGGER.warn("Failed to query external Tellus buildings from {}", this.path, error);
         return List.of();
      }
   }

   public List<ExternalAreaFeature> cityAreasForArea(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, double worldScale, int marginBlocks
   ) {
      GeoBounds bounds = blockBounds(minBlockX, minBlockZ, maxBlockX, maxBlockZ, worldScale, marginBlocks);
      if (bounds == null) {
         return List.of();
      }

      try {
         List<ExternalAreaFeature> areas = new ArrayList<>();
         areas.addAll(this.currentSource().areasForBounds(bounds));
         areas.addAll(this.pbfSource.areasForBounds(bounds));
         if (!this.pbfSource.coversBounds(bounds)) {
            areas.addAll(this.overpassSource.areasForBounds(bounds));
         }
         return areas.isEmpty() ? List.of() : List.copyOf(areas);
      } catch (RuntimeException error) {
         LOGGER.warn("Failed to query external Tellus city areas from {}", this.path, error);
         return List.of();
      }
   }

   public List<ExternalLineFeature> cityLinesForArea(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, double worldScale, int marginBlocks
   ) {
      GeoBounds bounds = blockBounds(minBlockX, minBlockZ, maxBlockX, maxBlockZ, worldScale, marginBlocks);
      if (bounds == null) {
         return List.of();
      }

      try {
         List<ExternalLineFeature> lines = new ArrayList<>();
         lines.addAll(this.currentSource().linesForBounds(bounds));
         lines.addAll(this.pbfSource.linesForBounds(bounds));
         if (!this.pbfSource.coversBounds(bounds)) {
            lines.addAll(this.overpassSource.linesForBounds(bounds));
         }
         return lines.isEmpty() ? List.of() : List.copyOf(lines);
      } catch (RuntimeException error) {
         LOGGER.warn("Failed to query external Tellus city lines from {}", this.path, error);
         return List.of();
      }
   }

   public List<ExternalPointFeature> cityPointsForArea(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, double worldScale, int marginBlocks
   ) {
      GeoBounds bounds = blockBounds(minBlockX, minBlockZ, maxBlockX, maxBlockZ, worldScale, marginBlocks);
      if (bounds == null) {
         return List.of();
      }

      try {
         List<ExternalPointFeature> points = new ArrayList<>();
         points.addAll(this.currentSource().pointsForBounds(bounds));
         points.addAll(this.pbfSource.pointsForBounds(bounds));
         if (!this.pbfSource.coversBounds(bounds)) {
            points.addAll(this.overpassSource.pointsForBounds(bounds));
         }
         return points.isEmpty() ? List.of() : List.copyOf(points);
      } catch (RuntimeException error) {
         LOGGER.warn("Failed to query external Tellus city points from {}", this.path, error);
         return List.of();
      }
   }

   private JsonExternalFeatureSource currentSource() {
      this.refreshIfNeeded();
      return this.source;
   }

   private void refreshIfNeeded() {
      long now = System.nanoTime();
      if (now < this.nextRefreshAtNanos) {
         return;
      }

      synchronized (this.refreshLock) {
         now = System.nanoTime();
         if (now < this.nextRefreshAtNanos) {
            return;
         }
         this.nextRefreshAtNanos = now + REFRESH_INTERVAL_NANOS;
         this.refresh();
      }
   }

   private void refresh() {
      FileState nextState = FileState.read(this.path);
      if (nextState.equals(this.fileState)) {
         return;
      }

      if (!nextState.exists()) {
         this.fileState = nextState;
         this.source = new JsonExternalFeatureSource(List.of(), List.of());
         return;
      }

      try {
         JsonExternalFeatureSource nextSource = JsonExternalFeatureSource.fromPath(this.path);
         this.source = nextSource;
         this.fileState = nextState;
         LOGGER.info(
            "Loaded external Tellus features from {} (roads={}, buildings={}, areas={}, lines={}, points={})",
            this.path,
            nextSource.roads().size(),
            nextSource.buildings().size(),
            nextSource.areas().size(),
            nextSource.lines().size(),
            nextSource.points().size()
         );
      } catch (IOException | RuntimeException error) {
         this.fileState = nextState;
         this.source = new JsonExternalFeatureSource(List.of(), List.of());
         LOGGER.warn("Failed to load external Tellus features from {}", this.path, error);
      }
   }

   private static GeoBounds blockBounds(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, double worldScale, int marginBlocks) {
      if (!(worldScale > 0.0)) {
         return null;
      }

      int margin = Math.max(0, marginBlocks);
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      if (!(blocksPerDegree > 0.0)) {
         return null;
      }

      double lonA = (Math.min(minBlockX, maxBlockX) - margin) / blocksPerDegree;
      double lonB = (Math.max(minBlockX, maxBlockX) + margin) / blocksPerDegree;
      double westRaw = Math.min(lonA, lonB);
      double eastRaw = Math.max(lonA, lonB);
      if (eastRaw < -180.0 || westRaw > 180.0) {
         return null;
      }

      double latA = EarthProjection.blockZToLat(Math.min(minBlockZ, maxBlockZ) - margin, worldScale);
      double latB = EarthProjection.blockZToLat(Math.max(minBlockZ, maxBlockZ) + margin, worldScale);
      double south = Math.min(latA, latB);
      double north = Math.max(latA, latB);
      double west = Math.max(-180.0, westRaw);
      double east = Math.min(180.0, eastRaw);
      if (west > east) {
         return null;
      }
      return new GeoBounds(south, west, north, east);
   }

   private record FileState(boolean exists, long modifiedMillis, long size) {
      private static FileState missing() {
         return new FileState(false, 0L, 0L);
      }

      private static FileState read(Path path) {
         try {
            if (!Files.isRegularFile(path)) {
               return missing();
            }
            return new FileState(true, Files.getLastModifiedTime(path).toMillis(), Files.size(path));
         } catch (IOException error) {
            LOGGER.warn("Failed to stat external Tellus feature file {}", path, error);
            return missing();
         }
      }
   }
}
