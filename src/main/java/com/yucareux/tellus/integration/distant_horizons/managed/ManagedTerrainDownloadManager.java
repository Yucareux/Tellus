package com.yucareux.tellus.integration.distant_horizons.managed;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.preload.TerrainPreloadArea;
import com.yucareux.tellus.preload.TerrainPreloadJob;
import com.yucareux.tellus.preload.TerrainPreloadJobManager;
import com.yucareux.tellus.preload.TerrainPreloadPackageRegistry;
import com.yucareux.tellus.preload.TerrainPreloadProgress;
import com.yucareux.tellus.preload.TerrainPreloadStage;
import com.yucareux.tellus.world.data.source.DownloadProgressReporter;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.WorldProjection;
import com.yucareux.tellus.worldgen.TellusWorldgenSources;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

public final class ManagedTerrainDownloadManager {
   private static final int DEFAULT_RENDER_RADIUS_CHUNKS = 128;
   private static final int MAX_CORE_ATTEMPTS = intProperty("tellus.managedDownloads.coreAttempts", 1, 1, 3);
   private static final int UPDATE_INTERVAL_TICKS = 20;
   private static final int COORDINATOR_THREADS = intProperty("tellus.managedDownloads.coordinators", 4, 1, 16);
   private static final int MIN_BATCH_CELLS_PER_SIDE = intProperty("tellus.managedDownloads.batchCellsPerSide", 8, 1, 64);
   private static final int MAX_BATCH_CELLS_PER_SIDE = intProperty(
      "tellus.managedDownloads.maxBatchCellsPerSide", 64, MIN_BATCH_CELLS_PER_SIDE, 127
   );
   private static final long PACKAGE_LOAD_TIMEOUT_MILLIS = intProperty(
      "tellus.managedDownloads.packageLoadTimeoutMs", 60_000, 1_000, 300_000
   );
   private static final long RETRY_BASE_DELAY_MILLIS = intProperty("tellus.managedDownloads.retryBaseDelayMs", 2_000, 250, 60_000);
   private static final long RETRY_MAX_DELAY_MILLIS = intProperty("tellus.managedDownloads.retryMaxDelayMs", 30_000, 1_000, 300_000);
   private final ExecutorService coordinators = Executors.newFixedThreadPool(COORDINATOR_THREADS, new ThreadFactory() {
      private final AtomicInteger index = new AtomicInteger();

      @Override
      public Thread newThread(Runnable runnable) {
         Thread thread = new Thread(runnable, "tellus-managed-terrain-" + this.index.incrementAndGet());
         thread.setDaemon(true);
         return thread;
      }
   });
   private final Map<UUID, Integer> requestedRenderRadii = new ConcurrentHashMap<>();
   private final Map<UUID, PlayerState> playerStates = new ConcurrentHashMap<>();
   private final Map<String, GeneratorState> generatorStates = new ConcurrentHashMap<>();
   private final Set<CellRequest> inFlight = ConcurrentHashMap.newKeySet();
   private final Set<BatchKey> batchesInFlight = ConcurrentHashMap.newKeySet();
   private final Map<BatchKey, TerrainPreloadJob> activePreloads = new ConcurrentHashMap<>();
   private final Map<CellRequest, RetryState> retries = new ConcurrentHashMap<>();
   private final AtomicLong sessionEpoch = new AtomicLong();
   private long tick;

   public void updateViewDistance(ServerPlayer player, int renderRadiusChunks) {
      Objects.requireNonNull(player, "player");
      this.requestedRenderRadii.put(
         player.getUUID(),
         Math.max(ManagedTerrainTarget.MIN_RENDER_RADIUS_CHUNKS, Math.min(ManagedTerrainTarget.MAX_RENDER_RADIUS_CHUNKS, renderRadiusChunks))
      );
   }

   public void onServerTick(MinecraftServer server) {
      Objects.requireNonNull(server, "server");
      if (++this.tick % UPDATE_INTERVAL_TICKS != 0L) {
         return;
      }

      Set<UUID> activePlayers = new HashSet<>();
      List<PlayerState> activeManagedStates = new ArrayList<>();
      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
         UUID playerId = player.getUUID();
         activePlayers.add(playerId);
         if (!(player.level() instanceof ServerLevel level)
            || !(level.getChunkSource().getGenerator() instanceof EarthChunkGenerator generator)) {
            this.playerStates.remove(playerId);
            continue;
         }

         EarthGeneratorSettings settings = generator.settings();
         if (!settings.tellusManagedTerrainDownloads()) {
            this.playerStates.put(playerId, PlayerState.disabled(settings.showTerrainDownloadOverlay()));
            continue;
         }
         if (!ManagedTerrainCompatibility.isDistantHorizonsPresent()) {
            this.playerStates.put(playerId, PlayerState.disabled(settings.showTerrainDownloadOverlay()));
            continue;
         }
         if (!ManagedTerrainCompatibility.isGenerationGateAvailable()) {
            this.playerStates.put(playerId, PlayerState.compatibilityFallback(settings.showTerrainDownloadOverlay()));
            continue;
         }

         ChunkPos playerChunk = player.chunkPosition();
         int chunkX = Math.floorDiv(playerChunk.getMinBlockX(), 16);
         int chunkZ = Math.floorDiv(playerChunk.getMinBlockZ(), 16);
         int renderRadius = this.requestedRenderRadii.getOrDefault(playerId, DEFAULT_RENDER_RADIUS_CHUNKS);
         String generatorKey = ManagedTerrainAvailability.key(generator);
         GeneratorState generatorState = this.generatorStates.compute(
            generatorKey,
            (ignored, existing) -> existing == null ? new GeneratorState(generatorKey, settings) : existing.updateSettings(settings)
         );
         PlayerState state = this.playerStates.compute(
            playerId,
            (ignored, existing) -> PlayerState.active(
               generatorKey,
               settings.showTerrainDownloadOverlay(),
               chunkX,
               chunkZ,
               renderRadius,
               existing
            )
         );
         generatorState.lastSeenTick = this.tick;
         activeManagedStates.add(state);
      }

      this.playerStates.entrySet().removeIf(entry -> !activePlayers.contains(entry.getKey()));
      this.requestedRenderRadii.keySet().removeIf(playerId -> !activePlayers.contains(playerId));
      this.generatorStates.entrySet().removeIf(entry -> this.tick - entry.getValue().lastSeenTick > 200L && !hasPlayerForGenerator(entry.getKey()));
      Map<String, Integer> progressiveBatchWidths = new HashMap<>();
      for (PlayerState state : activeManagedStates) {
         int widthChunks = downloadBatchCellsPerSide(state.target.renderRadiusChunks()) * ManagedTerrainCell.SIZE_CHUNKS;
         progressiveBatchWidths.merge(state.generatorKey, widthChunks, Math::min);
      }
      progressiveBatchWidths.forEach(ManagedTerrainAvailability::configureProgressiveBatchWidth);
      this.schedule(activeManagedStates);
      for (PlayerState state : activeManagedStates) {
         state.refreshStatus(this.inFlight, this.generatorStates.get(state.generatorKey));
      }
   }

   public Optional<ManagedTerrainDownloadStatus> statusFor(ServerPlayer player) {
      PlayerState state = this.playerStates.get(player.getUUID());
      if (state == null || !state.showOverlay) {
         return Optional.empty();
      }
      return Optional.of(state.status);
   }

   public boolean shouldBroadcastStatus() {
      return this.tick > 0L && this.tick % UPDATE_INTERVAL_TICKS == 0L;
   }

   public void onPlayerDisconnect(ServerPlayer player) {
      UUID id = player.getUUID();
      this.playerStates.remove(id);
      this.requestedRenderRadii.remove(id);
   }

   public void reset() {
      this.sessionEpoch.incrementAndGet();
      this.activePreloads.values().forEach(TerrainPreloadJob::cancel);
      this.activePreloads.clear();
      this.playerStates.clear();
      this.generatorStates.clear();
      this.requestedRenderRadii.clear();
      this.inFlight.clear();
      this.batchesInFlight.clear();
      this.retries.clear();
      ManagedTerrainAvailability.clearAll();
   }

   private boolean hasPlayerForGenerator(String generatorKey) {
      return this.playerStates.values().stream().anyMatch(state -> generatorKey.equals(state.generatorKey));
   }

   private void schedule(List<PlayerState> activeStates) {
      int availableSlots = Math.max(0, COORDINATOR_THREADS - this.batchesInFlight.size());
      if (availableSlots == 0 || activeStates.isEmpty()) {
         return;
      }

      for (PlayerState playerState : activeStates) {
         if (availableSlots <= 0) {
            return;
         }
         GeneratorState generatorState = this.generatorStates.get(playerState.generatorKey);
         if (generatorState == null || this.hasActivePreloadForGenerator(playerState.generatorKey)) {
            continue;
         }

         List<ManagedTerrainCell> targetCells = playerState.target.prioritizedCells(
            playerState.playerChunkX, playerState.playerChunkZ
         );
         long now = System.currentTimeMillis();
         Set<ManagedTerrainCell> pending = new HashSet<>();
         boolean anyReady = false;
         int nonReady = 0;
         for (ManagedTerrainCell cell : targetCells) {
            if (ManagedTerrainAvailability.isReady(playerState.generatorKey, cell)) {
               anyReady = true;
               continue;
            }
            if (ManagedTerrainAvailability.isFailed(playerState.generatorKey, cell)) {
               continue;
            }
            nonReady++;
            CellRequest cellRequest = new CellRequest(playerState.generatorKey, cell);
            if (!this.inFlight.contains(cellRequest) && this.retryReady(cellRequest, now)) {
               pending.add(cell);
            }
         }
         if (pending.isEmpty()) {
            continue;
         }

         // The first request uses the complete player-centered target and therefore gets the
         // exact Pre-load Terrain pipeline, including its adaptive processed package. Once
         // coverage exists, only preload a rectangular exposed strip so movement never rebuilds
         // the full package for a mostly-overlapping target.
         boolean buildPackage = !anyReady && pending.size() == nonReady;
         List<ManagedTerrainCell> requestedCells = buildPackage
            ? targetCells.stream().filter(pending::contains).toList()
            : nextPendingRectangle(targetCells, pending);
         BatchKey batchKey = BatchKey.forCells(playerState.generatorKey, requestedCells);
         if (!this.batchesInFlight.add(batchKey)) {
            continue;
         }

         CellProgress progress = new CellProgress();
         progress.active = true;
         for (ManagedTerrainCell cell : requestedCells) {
            this.inFlight.add(new CellRequest(playerState.generatorKey, cell));
            generatorState.progress.put(cell, progress);
         }
         long epoch = this.sessionEpoch.get();
         BatchRequest request = new BatchRequest(batchKey, requestedCells, buildPackage);
         this.coordinators.execute(() -> this.downloadBatch(generatorState, request, progress, epoch));
         availableSlots--;
      }
   }

   private boolean hasActivePreloadForGenerator(String generatorKey) {
      return this.batchesInFlight.stream().anyMatch(key -> key.generatorKey.equals(generatorKey));
   }

   static List<ManagedTerrainCell> nextPendingRectangle(
      List<ManagedTerrainCell> prioritizedCells, Set<ManagedTerrainCell> pending
   ) {
      ManagedTerrainCell seed = prioritizedCells.stream().filter(pending::contains).findFirst().orElseThrow();
      CellRun run = contiguousRun(pending, seed.z(), seed.x());
      int minZ = seed.z();
      int maxZ = seed.z();
      while (contiguousRun(pending, minZ - 1, seed.x()).equals(run)) {
         minZ--;
      }
      while (contiguousRun(pending, maxZ + 1, seed.x()).equals(run)) {
         maxZ++;
      }

      List<ManagedTerrainCell> rectangle = new ArrayList<>((run.maxX - run.minX + 1) * (maxZ - minZ + 1));
      for (int z = minZ; z <= maxZ; z++) {
         for (int x = run.minX; x <= run.maxX; x++) {
            rectangle.add(new ManagedTerrainCell(x, z));
         }
      }
      return List.copyOf(rectangle);
   }

   private static CellRun contiguousRun(Set<ManagedTerrainCell> cells, int z, int seedX) {
      if (!cells.contains(new ManagedTerrainCell(seedX, z))) {
         return CellRun.EMPTY;
      }
      int minX = seedX;
      int maxX = seedX;
      while (cells.contains(new ManagedTerrainCell(minX - 1, z))) {
         minX--;
      }
      while (cells.contains(new ManagedTerrainCell(maxX + 1, z))) {
         maxX++;
      }
      return new CellRun(minX, maxX);
   }

   private void downloadBatch(GeneratorState generatorState, BatchRequest request, CellProgress progress, long epoch) {
      RuntimeException failure = null;
      progress.startedAtMillis = System.currentTimeMillis();
      try {
         TerrainPreloadArea area = areaForCells(request.cells, generatorState.settings.projection());
         for (int attempt = 1; attempt <= MAX_CORE_ATTEMPTS; attempt++) {
            progress.detail = request.buildPackage
               ? "Running player-centered terrain preload (attempt " + attempt + "/" + MAX_CORE_ATTEMPTS + ")"
               : "Caching newly exposed terrain (attempt " + attempt + "/" + MAX_CORE_ATTEMPTS + ")";
            try {
               if (request.buildPackage) {
                  this.runPackagedPreload(generatorState, request, progress, area, epoch);
               } else {
                  runIncrementalPreload(generatorState, progress, area);
               }
               failure = null;
               break;
            } catch (RuntimeException error) {
               failure = error;
               if (attempt < MAX_CORE_ATTEMPTS) {
                  sleepBeforeRetry(attempt);
               }
            }
         }

         if (failure != null) {
            if (epoch != this.sessionEpoch.get()) {
               return;
            }
            RetryState retry = this.defer(generatorState.key, request.cells);
            progress.detail = "Terrain preload unavailable; retrying in " + formatDelay(retry.retryAtMillis - System.currentTimeMillis());
            Tellus.LOGGER.warn(
               "Managed terrain preload deferred for cells {}..{}/{}..{} ({} cells) after {} attempts; retry {} in {} ms",
               request.key.minX,
               request.key.maxX,
               request.key.minZ,
               request.key.maxZ,
               request.cells.size(),
               MAX_CORE_ATTEMPTS,
               retry.attempt,
               Math.max(0L, retry.retryAtMillis - System.currentTimeMillis()),
               failure
            );
            return;
         }

         progress.detail = request.buildPackage ? "Player-centered terrain package ready" : "New terrain coverage cached";
         if (epoch == this.sessionEpoch.get()) {
            for (ManagedTerrainCell cell : request.cells) {
               this.retries.remove(new CellRequest(generatorState.key, cell));
               ManagedTerrainAvailability.markReady(generatorState.key, cell, false);
            }
         }
         Tellus.LOGGER.info(
            "Managed terrain {} for cells {}..{}/{}..{} ({} cells) cached in {} ms ({} bytes read, {} bytes expected)",
            request.buildPackage ? "package" : "incremental area",
            request.key.minX,
            request.key.maxX,
            request.key.minZ,
            request.key.maxZ,
            request.cells.size(),
            System.currentTimeMillis() - progress.startedAtMillis,
            progress.bytesRead(),
            progress.bytesExpected()
         );
      } catch (RuntimeException error) {
         progress.detail = message(error);
         if (epoch != this.sessionEpoch.get()) {
            return;
         }
         RetryState retry = this.defer(generatorState.key, request.cells);
         Tellus.LOGGER.warn(
            "Managed terrain preload deferred for cells {}..{}/{}..{} ({} cells); retry {} in {} ms",
            request.key.minX,
            request.key.maxX,
            request.key.minZ,
            request.key.maxZ,
            request.cells.size(),
            retry.attempt,
            Math.max(0L, retry.retryAtMillis - System.currentTimeMillis()),
            error
         );
      } finally {
         progress.active = false;
         request.cells.forEach(cell -> this.inFlight.remove(new CellRequest(generatorState.key, cell)));
         this.activePreloads.remove(request.key);
         this.batchesInFlight.remove(request.key);
      }
   }

   private void runPackagedPreload(
      GeneratorState generatorState,
      BatchRequest request,
      CellProgress progress,
      TerrainPreloadArea area,
      long epoch
   ) {
      TerrainPreloadJob job = TerrainPreloadJobManager.instance().startAdditional(area, generatorState.settings);
      progress.preloadJob = job;
      this.activePreloads.put(request.key, job);
      TerrainPreloadProgress terminal = job.awaitCompletion();
      if (epoch != this.sessionEpoch.get()) {
         return;
      }
      if (terminal.stage() != TerrainPreloadStage.COMPLETE) {
         String detail = terminal.detail() == null || terminal.detail().isBlank() ? terminal.status() : terminal.detail();
         throw new RuntimeException(detail == null || detail.isBlank() ? "Player-centered terrain preload failed" : detail);
      }
      if (job.processedPackagePublished()) {
         this.awaitPublishedPackage(generatorState.settings, area, epoch);
      }
   }

   private static void runIncrementalPreload(
      GeneratorState generatorState, CellProgress progress, TerrainPreloadArea area
   ) {
      DownloadProgressReporter.Scope reporterScope = DownloadProgressReporter.push(progress);
      try (reporterScope) {
         TellusWorldgenSources.preloadAreaInputs(
            area.minBlockX(),
            area.minBlockZ(),
            area.maxBlockX(),
            area.maxBlockZ(),
            generatorState.settings,
            progress::onUnitProgress
         );
      }
   }

   private void awaitPublishedPackage(EarthGeneratorSettings settings, TerrainPreloadArea area, long epoch) {
      TerrainPreloadPackageRegistry.SettingsView view = TerrainPreloadPackageRegistry.instance().viewFor(settings);
      int probeX = area.minBlockX() + (area.maxBlockX() - area.minBlockX()) / 2;
      int probeZ = area.minBlockZ() + (area.maxBlockZ() - area.minBlockZ()) / 2;
      long deadline = saturatedAdd(System.currentTimeMillis(), PACKAGE_LOAD_TIMEOUT_MILLIS);
      while (epoch == this.sessionEpoch.get() && System.currentTimeMillis() < deadline) {
         if (view.contains(probeX, probeZ)) {
            return;
         }
         try {
            Thread.sleep(50L);
         } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while loading the processed terrain package", error);
         }
      }
      if (epoch == this.sessionEpoch.get()) {
         Tellus.LOGGER.warn(
            "Processed terrain package was published but did not enter the runtime registry within {} ms; raw cached inputs remain available",
            PACKAGE_LOAD_TIMEOUT_MILLIS
         );
      }
   }

   static TerrainPreloadArea areaForCells(List<ManagedTerrainCell> cells, WorldProjection projection) {
      int minChunkX = cells.stream().mapToInt(ManagedTerrainCell::minChunkX).min().orElseThrow();
      int minChunkZ = cells.stream().mapToInt(ManagedTerrainCell::minChunkZ).min().orElseThrow();
      int maxChunkX = cells.stream().mapToInt(ManagedTerrainCell::maxChunkX).max().orElseThrow();
      int maxChunkZ = cells.stream().mapToInt(ManagedTerrainCell::maxChunkZ).max().orElseThrow();
      double centerBlockX = ((long)minChunkX + maxChunkX + 1L) * 8.0;
      double centerBlockZ = ((long)minChunkZ + maxChunkZ + 1L) * 8.0;
      double longitude = projection.blockXToLon(centerBlockX);
      double latitude = projection.blockZToLat(centerBlockZ);
      return TerrainPreloadArea.fromChunkBounds(
         latitude,
         longitude,
         Math.max(maxChunkX - minChunkX + 1, maxChunkZ - minChunkZ + 1),
         projection,
         minChunkX,
         minChunkZ,
         maxChunkX,
         maxChunkZ
      );
   }

   static TerrainPreloadArea areaForCells(List<ManagedTerrainCell> cells, double worldScale) {
      return areaForCells(cells, WorldProjection.global(worldScale));
   }

   private static void sleepBeforeRetry(int attempt) {
      try {
         Thread.sleep(250L * attempt);
      } catch (InterruptedException error) {
         Thread.currentThread().interrupt();
         throw new RuntimeException("Managed terrain download interrupted", error);
      }
   }

   private boolean retryReady(CellRequest request, long nowMillis) {
      RetryState retry = this.retries.get(request);
      return retry == null || nowMillis >= retry.retryAtMillis;
   }

   private RetryState defer(String generatorKey, List<ManagedTerrainCell> cells) {
      long now = System.currentTimeMillis();
      RetryState[] latest = new RetryState[1];
      for (ManagedTerrainCell cell : cells) {
         CellRequest request = new CellRequest(generatorKey, cell);
         RetryState retry = this.retries.compute(request, (ignored, previous) -> {
            int attempt = previous == null ? 1 : previous.attempt + 1;
            return new RetryState(attempt, saturatedAdd(now, retryDelayMillis(attempt)));
         });
         if (latest[0] == null || retry.attempt > latest[0].attempt || retry.retryAtMillis > latest[0].retryAtMillis) {
            latest[0] = retry;
         }
      }
      return Objects.requireNonNull(latest[0], "managedTerrainRetry");
   }

   static long retryDelayMillis(int attempt) {
      int exponent = Math.max(0, Math.min(20, attempt - 1));
      long multiplier = 1L << exponent;
      long delay = RETRY_BASE_DELAY_MILLIS > Long.MAX_VALUE / multiplier
         ? Long.MAX_VALUE
         : RETRY_BASE_DELAY_MILLIS * multiplier;
      return Math.min(RETRY_MAX_DELAY_MILLIS, delay);
   }

   private static long saturatedAdd(long value, long amount) {
      return amount > Long.MAX_VALUE - value ? Long.MAX_VALUE : value + amount;
   }

   private static String formatDelay(long delayMillis) {
      long seconds = Math.max(1L, (Math.max(0L, delayMillis) + 999L) / 1_000L);
      return seconds + (seconds == 1L ? " second" : " seconds");
   }

   private static String message(Throwable error) {
      String message = error.getMessage();
      return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
   }

   private static int intProperty(String key, int defaultValue, int min, int max) {
      int value = Integer.getInteger(key, defaultValue);
      return Math.max(min, Math.min(max, value));
   }

   static int downloadBatchCellsPerSide(int renderRadiusChunks) {
      int radius = Math.max(ManagedTerrainTarget.MIN_RENDER_RADIUS_CHUNKS, Math.min(ManagedTerrainTarget.MAX_RENDER_RADIUS_CHUNKS, renderRadiusChunks));
      int requested = Math.max(MIN_BATCH_CELLS_PER_SIDE, divideCeil(radius, ManagedTerrainCell.SIZE_CHUNKS * 2));
      int powerOfTwo = Integer.highestOneBit(requested);
      if (powerOfTwo < requested) {
         powerOfTwo <<= 1;
      }
      int areaLimit = Math.max(1, TerrainPreloadArea.maxChunksPerSide() / ManagedTerrainCell.SIZE_CHUNKS);
      return Math.max(1, Math.min(Math.min(MAX_BATCH_CELLS_PER_SIDE, areaLimit), powerOfTwo));
   }

   private static int divideCeil(int value, int divisor) {
      return 1 + (value - 1) / divisor;
   }

   private record CellRequest(String generatorKey, ManagedTerrainCell cell) {
   }

   private record BatchKey(String generatorKey, int minX, int minZ, int maxX, int maxZ) {
      private static BatchKey forCells(String generatorKey, List<ManagedTerrainCell> cells) {
         return new BatchKey(
            generatorKey,
            cells.stream().mapToInt(ManagedTerrainCell::x).min().orElseThrow(),
            cells.stream().mapToInt(ManagedTerrainCell::z).min().orElseThrow(),
            cells.stream().mapToInt(ManagedTerrainCell::x).max().orElseThrow(),
            cells.stream().mapToInt(ManagedTerrainCell::z).max().orElseThrow()
         );
      }
   }

   private record RetryState(int attempt, long retryAtMillis) {
   }

   private record BatchRequest(BatchKey key, List<ManagedTerrainCell> cells, boolean buildPackage) {
      private BatchRequest {
         cells = List.copyOf(cells);
      }
   }

   private record CellRun(int minX, int maxX) {
      private static final CellRun EMPTY = new CellRun(1, 0);
   }

   private static final class GeneratorState {
      private final String key;
      private final Map<ManagedTerrainCell, CellProgress> progress = new ConcurrentHashMap<>();
      private volatile EarthGeneratorSettings settings;
      private volatile long lastSeenTick;

      private GeneratorState(String key, EarthGeneratorSettings settings) {
         this.key = key;
         this.settings = settings;
      }

      private GeneratorState updateSettings(EarthGeneratorSettings settings) {
         this.settings = settings;
         return this;
      }
   }

   private static final class PlayerState {
      private final String generatorKey;
      private final boolean showOverlay;
      private final int playerChunkX;
      private final int playerChunkZ;
      private final ManagedTerrainTarget target;
      private volatile ManagedTerrainDownloadStatus status;

      private PlayerState(
         String generatorKey,
         boolean showOverlay,
         int playerChunkX,
         int playerChunkZ,
         ManagedTerrainTarget target,
         ManagedTerrainDownloadStatus status
      ) {
         this.generatorKey = generatorKey;
         this.showOverlay = showOverlay;
         this.playerChunkX = playerChunkX;
         this.playerChunkZ = playerChunkZ;
         this.target = target;
         this.status = status;
      }

      private static PlayerState active(
         String generatorKey,
         boolean showOverlay,
         int playerChunkX,
         int playerChunkZ,
         int renderRadius,
         PlayerState previous
      ) {
         ManagedTerrainTarget target = previous != null && generatorKey.equals(previous.generatorKey) && previous.target != null
            ? previous.target.update(playerChunkX, playerChunkZ, renderRadius)
            : ManagedTerrainTarget.initial(playerChunkX, playerChunkZ, renderRadius);
         ManagedTerrainDownloadStatus planning = new ManagedTerrainDownloadStatus(
            ManagedTerrainDownloadStatus.Stage.PLANNING,
            0,
            target.prioritizedCells(playerChunkX, playerChunkZ).size(),
            0,
            0,
            0,
            0L,
            -1L,
            target.renderRadiusChunks(),
            target.safetyRingChunks(),
            "Planning player-centered terrain cache"
         );
         return new PlayerState(generatorKey, showOverlay, playerChunkX, playerChunkZ, target, planning);
      }

      private static PlayerState disabled(boolean showOverlay) {
         return terminal(showOverlay, ManagedTerrainDownloadStatus.Stage.DISABLED, "Legacy Distant Horizons downloading is enabled");
      }

      private static PlayerState compatibilityFallback(boolean showOverlay) {
         return terminal(
            showOverlay,
            ManagedTerrainDownloadStatus.Stage.COMPATIBILITY_FALLBACK,
            "Tellus-managed downloads require the Tellus Distant Horizons generation gate; using legacy mode"
         );
      }

      private static PlayerState terminal(boolean showOverlay, ManagedTerrainDownloadStatus.Stage stage, String detail) {
         return new PlayerState(
            "",
            showOverlay,
            0,
            0,
            null,
            new ManagedTerrainDownloadStatus(stage, 0, 0, 0, 0, 0, 0L, -1L, 0, 0, detail)
         );
      }

      private void refreshStatus(Set<CellRequest> inFlight, GeneratorState generatorState) {
         List<ManagedTerrainCell> cells = this.target.prioritizedCells(this.playerChunkX, this.playerChunkZ);
         int completed = 0;
         int degraded = 0;
         int failed = 0;
         int active = 0;
         long bytesRead = 0L;
         long bytesExpected = 0L;
         Set<CellProgress> countedProgress = Collections.newSetFromMap(new IdentityHashMap<>());
         String detail = "Caching terrain around the player";
         for (ManagedTerrainCell cell : cells) {
            if (ManagedTerrainAvailability.isReady(this.generatorKey, cell)) {
               completed++;
               if (ManagedTerrainAvailability.isDegraded(this.generatorKey, cell)) {
                  degraded++;
               }
            } else if (ManagedTerrainAvailability.isFailed(this.generatorKey, cell)) {
               failed++;
            }
            if (inFlight.contains(new CellRequest(this.generatorKey, cell))) {
               active++;
            }
            if (generatorState != null) {
               CellProgress progress = generatorState.progress.get(cell);
               if (progress != null && progress.active && countedProgress.add(progress)) {
                  bytesRead += progress.bytesRead();
                  long expected = progress.bytesExpected();
                  if (expected > 0L) {
                     bytesExpected += expected;
                  }
                  String progressDetail = progress.detail();
                  if (progressDetail != null && !progressDetail.isBlank()) {
                     detail = progressDetail;
                  }
               }
            }
         }
         ManagedTerrainDownloadStatus.Stage stage;
         if (failed > 0) {
            stage = ManagedTerrainDownloadStatus.Stage.FAILED;
            detail = "Required terrain data failed after retries";
         } else if (completed == cells.size()) {
            stage = degraded > 0 ? ManagedTerrainDownloadStatus.Stage.DEGRADED : ManagedTerrainDownloadStatus.Stage.COMPLETE;
            detail = degraded > 0 ? "Terrain cached with optional data fallbacks" : "Terrain cache is ready";
         } else {
            stage = ManagedTerrainDownloadStatus.Stage.DOWNLOADING;
         }
         this.status = new ManagedTerrainDownloadStatus(
            stage,
            completed,
            cells.size(),
            active,
            failed,
            degraded,
            bytesRead,
            bytesExpected > 0L ? bytesExpected : -1L,
            this.target.renderRadiusChunks(),
            this.target.safetyRingChunks(),
            detail
         );
      }
   }

   private static final class CellProgress implements DownloadProgressReporter.Listener {
      private final AtomicLong bytesRead = new AtomicLong();
      private final AtomicLong bytesExpected = new AtomicLong();
      private volatile boolean active;
      private volatile long startedAtMillis;
      private volatile String detail = "Queued";
      private volatile TerrainPreloadJob preloadJob;

      @Override
      public void onExpectedBytesKnown(long expectedBytes) {
         if (expectedBytes > 0L) {
            this.bytesExpected.addAndGet(expectedBytes);
         }
      }

      @Override
      public void onRequestStarted(long expectedBytes) {
         if (expectedBytes > 0L) {
            this.bytesExpected.addAndGet(expectedBytes);
         }
      }

      @Override
      public void onBytesRead(int bytes) {
         this.bytesRead.addAndGet(Math.max(0, bytes));
      }

      @Override
      public void onRequestFinished() {
      }

      private void onUnitProgress(int completed, String detail) {
         if (detail != null && !detail.isBlank()) {
            this.detail = detail;
         }
      }

      private long bytesRead() {
         TerrainPreloadJob job = this.preloadJob;
         return job == null ? this.bytesRead.get() : job.progress().bytesRead();
      }

      private long bytesExpected() {
         TerrainPreloadJob job = this.preloadJob;
         return job == null ? this.bytesExpected.get() : job.progress().bytesExpected();
      }

      private String detail() {
         TerrainPreloadJob job = this.preloadJob;
         if (job == null) {
            return this.detail;
         }
         TerrainPreloadProgress current = job.progress();
         return current.detail() == null || current.detail().isBlank() ? current.status() : current.detail();
      }
   }
}
