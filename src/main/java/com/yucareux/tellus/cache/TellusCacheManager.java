package com.yucareux.tellus.cache;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.platform.TellusPlatform;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Asynchronously measures and deletes Tellus disk caches for the client cache settings UI.
 *
 * <p>Deletion is deliberately performed in place instead of moving a cache directory aside.
 * That allows cancellation to leave every untouched cache file at its normal path. Files that
 * were deleted before cancellation remain deleted and can be downloaded or rebuilt as usual.</p>
 */
public final class TellusCacheManager {
   private static final long PROGRESS_PUBLISH_INTERVAL_NANOS = 40_000_000L;
   private static final Object OPERATION_LOCK = new Object();
   private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(new CacheThreadFactory());
   private static final AtomicReference<Snapshot> SNAPSHOT = new AtomicReference<>(Snapshot.notStarted());
   private static boolean operationInFlight;
   private static DeletionTask currentDeletion;

   private TellusCacheManager() {
   }

   public static Snapshot snapshot() {
      return SNAPSHOT.get();
   }

   public static void requestRefresh() {
      synchronized (OPERATION_LOCK) {
         if (operationInFlight) {
            return;
         }

         operationInFlight = true;
         SNAPSHOT.set(Snapshot.calculating(Map.of(), 0L, scanUnitCount()));
      }

      EXECUTOR.execute(TellusCacheManager::runRefresh);
   }

   public static void delete(Metric metric) {
      Objects.requireNonNull(metric, "metric");
      if (metric == Metric.TOTAL) {
         return;
      }

      startDeletion(metric, false);
   }

   public static void deleteAll() {
      startDeletion(null, true);
   }

   public static void cancelDeletion() {
      synchronized (OPERATION_LOCK) {
         if (currentDeletion == null || currentDeletion.cancelled.get()) {
            return;
         }

         currentDeletion.cancelled.set(true);
         Snapshot snapshot = SNAPSHOT.get();
         if (snapshot.status() == Status.DELETING) {
            SNAPSHOT.set(snapshot.withCancelling());
         }
      }
   }

   private static void startDeletion(Metric metric, boolean deleteAll) {
      DeletionTask task;
      synchronized (OPERATION_LOCK) {
         Snapshot snapshot = SNAPSHOT.get();
         long totalFiles = deleteAll ? snapshot.totalFiles() : snapshot.filesFor(metric);
         if (operationInFlight || !snapshot.ready() || totalFiles <= 0L) {
            return;
         }

         task = new DeletionTask(metric, deleteAll, snapshot.usage(), totalFiles);
         operationInFlight = true;
         currentDeletion = task;
         SNAPSHOT.set(Snapshot.deleting(task.usage, metric, deleteAll, 0L, totalFiles, false));
      }

      EXECUTOR.execute(() -> runDeletion(task));
   }

   private static void runRefresh() {
      Snapshot result;
      try {
         result = computeSnapshot();
      } catch (RuntimeException error) {
         Tellus.LOGGER.warn("Failed to calculate Tellus cache usage", error);
         result = Snapshot.failed(SNAPSHOT.get().usage());
      }

      synchronized (OPERATION_LOCK) {
         SNAPSHOT.set(result);
         operationInFlight = false;
      }
   }

   private static void runDeletion(DeletionTask task) {
      try {
         if (task.deleteAll) {
            TellusCacheRegistry.clearAll();
            deleteMetrics(dataMetrics(), task);
         } else if (task.metric != null) {
            task.metric.clearRuntimeCache();
            deleteMetrics(List.of(task.metric), task);
         }
      } catch (RuntimeException error) {
         Tellus.LOGGER.warn("Failed while deleting Tellus cache", error);
      }

      Snapshot result;
      try {
         result = computeSnapshot();
      } catch (RuntimeException error) {
         Tellus.LOGGER.warn("Failed to recalculate Tellus cache usage after deletion", error);
         result = Snapshot.failed(task.usage);
      }

      synchronized (OPERATION_LOCK) {
         if (currentDeletion == task) {
            currentDeletion = null;
         }
         SNAPSHOT.set(result);
         operationInFlight = false;
      }
   }

   private static void deleteMetrics(List<Metric> metrics, DeletionTask task) {
      DeletionProgress progress = new DeletionProgress(task);
      for (Metric metric : metrics) {
         for (Path path : metric.resolvePaths()) {
            if (task.cancelled.get()) {
               progress.publish(true);
               return;
            }

            deletePath(path, task.cancelled, progress);
         }
      }

      progress.finish();
   }

   private static Snapshot computeSnapshot() {
      EnumMap<Metric, Usage> usage = new EnumMap<>(Metric.class);
      long completedUnits = 0L;
      long totalUnits = scanUnitCount();
      SNAPSHOT.set(Snapshot.calculating(usage, completedUnits, totalUnits));

      for (Metric metric : dataMetrics()) {
         long bytes = 0L;
         long files = 0L;
         for (Path path : metric.resolvePaths()) {
            Usage pathUsage = scanPath(path);
            bytes = saturatedAdd(bytes, pathUsage.bytes());
            files = saturatedAdd(files, pathUsage.files());
            usage.put(metric, new Usage(bytes, files));
            completedUnits++;
            SNAPSHOT.set(Snapshot.calculating(usage, completedUnits, totalUnits));
         }

         usage.putIfAbsent(metric, Usage.EMPTY);
      }

      return Snapshot.ready(usage);
   }

   private static Usage scanPath(Path root) {
      if (!Files.exists(root)) {
         return Usage.EMPTY;
      }

      long[] totals = new long[2];
      try {
         Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
               totals[0] = saturatedAdd(totals[0], Math.max(0L, attributes.size()));
               totals[1] = saturatedAdd(totals[1], 1L);
               return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) {
               Tellus.LOGGER.debug("Unable to inspect cache file {}", file, error);
               return FileVisitResult.CONTINUE;
            }
         });
      } catch (IOException error) {
         Tellus.LOGGER.warn("Failed to scan cache at {}", root, error);
      }

      return new Usage(totals[0], totals[1]);
   }

   private static void deletePath(Path root, AtomicBoolean cancelled, DeletionProgress progress) {
      if (!Files.exists(root) || cancelled.get()) {
         return;
      }

      try {
         Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
               return cancelled.get() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
               if (cancelled.get()) {
                  return FileVisitResult.TERMINATE;
               }

               try {
                  if (Files.deleteIfExists(file)) {
                     progress.fileDeleted();
                  }
               } catch (IOException error) {
                  Tellus.LOGGER.warn("Failed to delete cache file {}", file, error);
               }
               return cancelled.get() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) {
               if (!cancelled.get()) {
                  Tellus.LOGGER.warn("Failed to access cache file {} for deletion", file, error);
               }
               return cancelled.get() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException error) {
               if (cancelled.get()) {
                  return FileVisitResult.TERMINATE;
               }
               if (error != null) {
                  Tellus.LOGGER.warn("Failed while visiting cache folder {}", directory, error);
                  return FileVisitResult.CONTINUE;
               }

               try {
                  Files.deleteIfExists(directory);
               } catch (IOException deleteError) {
                  Tellus.LOGGER.debug("Cache folder {} was not empty after deletion", directory, deleteError);
               }
               return FileVisitResult.CONTINUE;
            }
         });
      } catch (IOException error) {
         Tellus.LOGGER.warn("Failed to delete cache folder {}", root, error);
      }
   }

   private static List<Metric> dataMetrics() {
      return List.of(
         Metric.OSM,
         Metric.LAND_COVER,
         Metric.CANOPY_HEIGHT,
         Metric.KOPPEN,
         Metric.TERRAIN,
         Metric.OPENWATERS,
         Metric.OISST,
         Metric.PRELOADED_TERRAIN,
         Metric.GEOLOGY
      );
   }

   private static long scanUnitCount() {
      long total = 0L;
      for (Metric metric : dataMetrics()) {
         total += metric.relativePaths.length;
      }
      return Math.max(1L, total);
   }

   private static long saturatedAdd(long left, long right) {
      if (right > 0L && left > Long.MAX_VALUE - right) {
         return Long.MAX_VALUE;
      }
      return left + right;
   }

   public enum Status {
      NOT_STARTED,
      CALCULATING,
      DELETING,
      READY,
      FAILED
   }

   public enum Metric {
      OSM("tellus.cache.section.osm", new String[]{"tellus/cache/map"}, new TellusCacheDomain[]{TellusCacheDomain.OSM}),
      LAND_COVER(
         "tellus.cache.section.land_cover",
         new String[]{"tellus/cache/worldcover-2021-v200-range", "tellus/cache/land-cover-overture", "tellus/cache/worldcover2021"},
         new TellusCacheDomain[]{TellusCacheDomain.LAND_COVER}
      ),
      CANOPY_HEIGHT(
         "tellus.cache.section.canopy_height",
         new String[]{"tellus/cache/canopy-height-eth-2020-v1"},
         new TellusCacheDomain[]{TellusCacheDomain.CANOPY_HEIGHT}
      ),
      KOPPEN("tellus.cache.section.koppen", new String[]{"tellus/cache/koppen"}, new TellusCacheDomain[]{TellusCacheDomain.KOPPEN}),
      TERRAIN(
         "tellus.cache.section.terrain",
         new String[]{
            "tellus/cache/elevation-mapterhorn",
            "tellus/cache/elevation-mapterhorn-coverage",
            "tellus/cache/elevation-tellus",
            "tellus/cache/elevation-normalized"
         },
         new TellusCacheDomain[]{TellusCacheDomain.TERRAIN, TellusCacheDomain.NORMALIZED_TERRAIN}
      ),
      OPENWATERS(
         "tellus.cache.section.openwaters",
         new String[]{"tellus/cache/elevation-openwaters"},
         new TellusCacheDomain[]{TellusCacheDomain.OPENWATERS}
      ),
      OISST(
         "tellus.cache.section.oisst",
         new String[]{"tellus/cache/ocean-oisst-v21"},
         new TellusCacheDomain[]{TellusCacheDomain.OISST}
      ),
      PRELOADED_TERRAIN(
         "tellus.cache.section.preloaded_terrain",
         new String[]{"tellus/cache/preloaded-terrain/v1"},
         new TellusCacheDomain[]{TellusCacheDomain.PRELOADED_TERRAIN}
      ),
      GEOLOGY("tellus.cache.section.geology", new String[]{"tellus/cache/geology"}, new TellusCacheDomain[]{TellusCacheDomain.GEOLOGY}),
      TOTAL("tellus.cache.section.total", new String[0], new TellusCacheDomain[0]);

      private final String labelKey;
      private final String[] relativePaths;
      private final TellusCacheDomain[] domains;

      Metric(String labelKey, String[] relativePaths, TellusCacheDomain[] domains) {
         this.labelKey = Objects.requireNonNull(labelKey, "labelKey");
         this.relativePaths = Objects.requireNonNull(relativePaths, "relativePaths");
         this.domains = Objects.requireNonNull(domains, "domains");
      }

      public String labelKey() {
         return this.labelKey;
      }

      private List<Path> resolvePaths() {
         Path gameDirectory = TellusPlatform.gameDir();
         return java.util.Arrays.stream(this.relativePaths).map(gameDirectory::resolve).toList();
      }

      private void clearRuntimeCache() {
         for (TellusCacheDomain domain : this.domains) {
            TellusCacheRegistry.clear(domain);
         }
      }
   }

   public record Usage(long bytes, long files) {
      private static final Usage EMPTY = new Usage(0L, 0L);

      public Usage {
         bytes = Math.max(0L, bytes);
         files = Math.max(0L, files);
      }
   }

   public record Snapshot(
      Status status,
      Map<Metric, Usage> usage,
      long completedUnits,
      long totalUnits,
      Metric deletionTarget,
      boolean deletingAll,
      boolean cancelling
   ) {
      public Snapshot {
         status = Objects.requireNonNull(status, "status");
         usage = Map.copyOf(Objects.requireNonNull(usage, "usage"));
         completedUnits = Math.max(0L, completedUnits);
         totalUnits = Math.max(0L, totalUnits);
      }

      private static Snapshot notStarted() {
         return new Snapshot(Status.NOT_STARTED, Map.of(), 0L, 0L, null, false, false);
      }

      private static Snapshot calculating(Map<Metric, Usage> usage, long completedUnits, long totalUnits) {
         return new Snapshot(Status.CALCULATING, usage, completedUnits, totalUnits, null, false, false);
      }

      private static Snapshot deleting(
         Map<Metric, Usage> usage,
         Metric deletionTarget,
         boolean deletingAll,
         long completedUnits,
         long totalUnits,
         boolean cancelling
      ) {
         return new Snapshot(Status.DELETING, usage, completedUnits, totalUnits, deletionTarget, deletingAll, cancelling);
      }

      private static Snapshot ready(Map<Metric, Usage> usage) {
         return new Snapshot(Status.READY, usage, 1L, 1L, null, false, false);
      }

      private static Snapshot failed(Map<Metric, Usage> usage) {
         return new Snapshot(Status.FAILED, usage, 0L, 0L, null, false, false);
      }

      private Snapshot withCancelling() {
         return deleting(this.usage, this.deletionTarget, this.deletingAll, this.completedUnits, this.totalUnits, true);
      }

      public boolean ready() {
         return this.status == Status.READY;
      }

      public boolean hasUsageFor(Metric metric) {
         return metric == Metric.TOTAL ? !this.usage.isEmpty() : this.usage.containsKey(metric);
      }

      public long bytesFor(Metric metric) {
         if (metric == Metric.TOTAL) {
            return this.totalBytes();
         }
         return this.usage.getOrDefault(metric, Usage.EMPTY).bytes();
      }

      public long filesFor(Metric metric) {
         if (metric == Metric.TOTAL) {
            return this.totalFiles();
         }
         return this.usage.getOrDefault(metric, Usage.EMPTY).files();
      }

      public long totalBytes() {
         long total = 0L;
         for (Metric metric : dataMetrics()) {
            total = saturatedAdd(total, this.bytesFor(metric));
         }
         return total;
      }

      public long totalFiles() {
         long total = 0L;
         for (Metric metric : dataMetrics()) {
            total = saturatedAdd(total, this.filesFor(metric));
         }
         return total;
      }

      public double progress() {
         if (this.totalUnits <= 0L) {
            return 0.0;
         }
         return Math.max(0.0, Math.min(1.0, (double)this.completedUnits / (double)this.totalUnits));
      }
   }

   private static final class DeletionTask {
      private final Metric metric;
      private final boolean deleteAll;
      private final Map<Metric, Usage> usage;
      private final long totalFiles;
      private final AtomicBoolean cancelled = new AtomicBoolean(false);

      private DeletionTask(Metric metric, boolean deleteAll, Map<Metric, Usage> usage, long totalFiles) {
         this.metric = metric;
         this.deleteAll = deleteAll;
         this.usage = usage;
         this.totalFiles = totalFiles;
      }
   }

   private static final class DeletionProgress {
      private final DeletionTask task;
      private long deletedFiles;
      private long lastPublishNanos;

      private DeletionProgress(DeletionTask task) {
         this.task = task;
      }

      private void fileDeleted() {
         this.deletedFiles = saturatedAdd(this.deletedFiles, 1L);
         this.publish(false);
      }

      private void finish() {
         this.deletedFiles = Math.max(this.deletedFiles, this.task.totalFiles);
         this.publish(true);
      }

      private void publish(boolean force) {
         long now = System.nanoTime();
         if (!force && now - this.lastPublishNanos < PROGRESS_PUBLISH_INTERVAL_NANOS) {
            return;
         }

         this.lastPublishNanos = now;
         SNAPSHOT.set(
            Snapshot.deleting(
               this.task.usage,
               this.task.metric,
               this.task.deleteAll,
               Math.min(this.deletedFiles, this.task.totalFiles),
               this.task.totalFiles,
               this.task.cancelled.get()
            )
         );
      }
   }

   private static final class CacheThreadFactory implements ThreadFactory {
      private int index;

      @Override
      public Thread newThread(Runnable runnable) {
         Thread thread = new Thread(runnable, "tellus-cache-" + ++this.index);
         thread.setDaemon(true);
         return thread;
      }
   }
}
