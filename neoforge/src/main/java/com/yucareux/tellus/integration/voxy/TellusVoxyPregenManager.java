package com.yucareux.tellus.integration.voxy;

import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

public final class TellusVoxyPregenManager {
   private static final long TICK_NANOS = 50000000L;
   private static final long SOFT_OVERLOAD_TICK_NANOS = 55000000L;
   private static final long HARD_OVERLOAD_TICK_NANOS = 70000000L;
   private static final int MAX_QUEUE_SIZE = 50000;
   private static final int IN_FLIGHT_MULTIPLIER = 4;
   private static final int ABSOLUTE_MAX_IN_FLIGHT = 256;
   private static final int COMPLETED_CACHE_MAX = 200000;
   private static final int TELEPORT_REPRIORITIZE_DISTANCE_CHUNKS = 64;
   private static final int MOVEMENT_REPRIORITIZE_DISTANCE_CHUNKS = 16;
   private static final int REPRIORITIZE_IN_FLIGHT_KEEP_RADIUS_CHUNKS = 16;
   private static final int REPRIORITIZE_RADIUS_PADDING_CHUNKS = 8;
   private static final int PRIORITY_RADIUS_CHUNKS = 2;
   private static final int STALE_QUEUE_PRUNE_TRIGGER = 10000;
   private final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
   private final LongOpenHashSet queued = new LongOpenHashSet();
   private final LongArrayFIFOQueue priorityQueue = new LongArrayFIFOQueue();
   private final LongOpenHashSet priorityQueued = new LongOpenHashSet();
   private final LongLinkedOpenHashSet completedChunks = new LongLinkedOpenHashSet();
   private final Map<UUID, TellusVoxyPregenManager.PlayerPregenState> playerStates = new HashMap<>();
   private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
   
   private volatile Boolean enabledOverride;
   
   private volatile Integer maxRadiusOverride;
   
   private volatile Integer chunksPerTickOverride;
   private volatile int lastConfiguredVoxyRadiusChunks;
   private volatile int lastEffectiveRadiusChunks;

   public void onServerTick(MinecraftServer server) {
      ServerLevel level = server.getLevel(Level.OVERWORLD);
      if (level == null) {
         this.clearQueueAndPlayerState();
         this.lastConfiguredVoxyRadiusChunks = 0;
         this.lastEffectiveRadiusChunks = 0;
      } else if (level.getChunkSource().getGenerator() instanceof EarthChunkGenerator earthGenerator) {
         EarthGeneratorSettings var11 = earthGenerator.settings();
         if (!this.effectiveEnabled(var11)) {
            this.clearQueueAndPlayerState();
            this.lastConfiguredVoxyRadiusChunks = 0;
            this.lastEffectiveRadiusChunks = 0;
         } else if (!VoxyBridge.isVoxyLoaded()) {
            this.clearQueueAndPlayerState();
            this.lastConfiguredVoxyRadiusChunks = 0;
            this.lastEffectiveRadiusChunks = 0;
         } else {
            int configuredVoxyRadius = Math.max(0, VoxyBridge.configuredChunkRadius());
            this.lastConfiguredVoxyRadiusChunks = configuredVoxyRadius;
            int targetRadius = Math.min(configuredVoxyRadius, this.effectiveMaxRadius(var11));
            this.lastEffectiveRadiusChunks = targetRadius;
            if (targetRadius <= 0) {
               this.clearQueueAndPlayerState();
            } else {
               List<ServerPlayer> players = overworldPlayers(server, level);
               if (players.isEmpty()) {
                  this.clearQueueAndPlayerState();
               } else {
                  boolean movedPlayers = this.enqueueAroundPlayers(players, targetRadius);
                  if (movedPlayers) {
                     this.refreshPriorityQueue(players, targetRadius);
                     if (this.queue.size() >= STALE_QUEUE_PRUNE_TRIGGER) {
                        this.pruneNormalQueue(players, targetRadius + REPRIORITIZE_RADIUS_PADDING_CHUNKS);
                     }
                  }

                  long averageTickNanos = server.getAverageTickTimeNanos();
                  this.processQueue(server, level.getChunkSource(), this.effectiveChunksPerTick(var11), averageTickNanos);
               }
            }
         }
      } else {
         this.clearQueueAndPlayerState();
         this.lastConfiguredVoxyRadiusChunks = 0;
         this.lastEffectiveRadiusChunks = 0;
      }
   }

   public void shutdown() {
      this.clearQueueAndPlayerState();
      this.inFlight.clear();
      this.clearOverrides();
      this.lastConfiguredVoxyRadiusChunks = 0;
      this.lastEffectiveRadiusChunks = 0;
   }

   public void clearOverrides() {
      this.enabledOverride = null;
      this.maxRadiusOverride = null;
      this.chunksPerTickOverride = null;
   }

   public void setEnabledOverride( Boolean enabled) {
      this.enabledOverride = enabled;
   }

   public void setMaxRadiusOverride( Integer maxRadius) {
      this.maxRadiusOverride = maxRadius;
   }

   public void setChunksPerTickOverride( Integer chunksPerTick) {
      this.chunksPerTickOverride = chunksPerTick;
   }

   public boolean effectiveEnabled(EarthGeneratorSettings settings) {
      Boolean override = this.enabledOverride;
      return override != null ? override : settings.voxyChunkPregenEnabled();
   }

   public int effectiveMaxRadius(EarthGeneratorSettings settings) {
      Integer override = this.maxRadiusOverride;
      return Math.max(0, override != null ? override : settings.voxyChunkPregenMaxRadius());
   }

   public int effectiveChunksPerTick(EarthGeneratorSettings settings) {
      Integer override = this.chunksPerTickOverride;
      return Math.max(1, override != null ? override : settings.voxyChunkPregenChunksPerTick());
   }

   
   public Boolean enabledOverride() {
      return this.enabledOverride;
   }

   
   public Integer maxRadiusOverride() {
      return this.maxRadiusOverride;
   }

   
   public Integer chunksPerTickOverride() {
      return this.chunksPerTickOverride;
   }

   public int queuedChunkCount() {
      return this.queue.size() + this.priorityQueue.size();
   }

   public int inFlightChunkCount() {
      return this.inFlight.size();
   }

   public int lastConfiguredVoxyRadiusChunks() {
      return this.lastConfiguredVoxyRadiusChunks;
   }

   public int lastEffectiveRadiusChunks() {
      return this.lastEffectiveRadiusChunks;
   }

   private static List<ServerPlayer> overworldPlayers(MinecraftServer server, ServerLevel level) {
      List<ServerPlayer> players = new ArrayList<>();

      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
         if (player.level() == level) {
            players.add(player);
         }
      }

      return players;
   }

   private boolean enqueueAroundPlayers(List<ServerPlayer> players, int radius) {
      Set<UUID> active = new HashSet<>();
      boolean reprioritize = false;
      boolean movedPlayers = false;

      for (ServerPlayer player : players) {
         active.add(player.getUUID());
         TellusVoxyPregenManager.PlayerPregenState state = this.playerStates
            .computeIfAbsent(player.getUUID(), ignored -> new TellusVoxyPregenManager.PlayerPregenState());
         ChunkPos center = player.chunkPosition();
         if (state.centerX != center.x || state.centerZ != center.z) {
            movedPlayers = true;
            if (state.centerX != Integer.MIN_VALUE && state.centerZ != Integer.MIN_VALUE) {
               int jumpDistance = chebyshevChunkDistance(state.centerX, state.centerZ, center.x, center.z);
               boolean requiresReprioritize = jumpDistance >= TELEPORT_REPRIORITIZE_DISTANCE_CHUNKS;
               if (!requiresReprioritize) {
                  if (state.anchorX == Integer.MIN_VALUE || state.anchorZ == Integer.MIN_VALUE) {
                     state.anchorX = state.centerX;
                     state.anchorZ = state.centerZ;
                  }

                  int driftFromAnchor = chebyshevChunkDistance(state.anchorX, state.anchorZ, center.x, center.z);
                  requiresReprioritize = driftFromAnchor >= MOVEMENT_REPRIORITIZE_DISTANCE_CHUNKS;
               }

               if (requiresReprioritize) {
                  reprioritize = true;
                  state.nextRing = 0;
                  state.anchorX = center.x;
                  state.anchorZ = center.z;
               } else {
                  state.nextRing = Math.max(0, state.nextRing - jumpDistance);
               }

               state.centerX = center.x;
               state.centerZ = center.z;
            } else {
               state.centerX = center.x;
               state.centerZ = center.z;
               state.anchorX = center.x;
               state.anchorZ = center.z;
               state.nextRing = 0;
            }
         }
      }

      this.playerStates.entrySet().removeIf(entry -> !active.contains(entry.getKey()));
      if (reprioritize) {
         this.reprioritizeForCurrentPlayers(players, radius);
      }

      for (ServerPlayer playerx : players) {
         TellusVoxyPregenManager.PlayerPregenState state = this.playerStates.get(playerx.getUUID());
         if (state != null && state.nextRing <= radius && this.queue.size() < MAX_QUEUE_SIZE) {
            this.enqueueRing(state.centerX, state.centerZ, state.nextRing);
            state.nextRing++;
         }
      }

      return movedPlayers;
   }

   private void reprioritizeForCurrentPlayers(List<ServerPlayer> players, int radius) {
      this.queue.clear();
      this.queued.clear();
      int keepRadius = Math.min(radius + REPRIORITIZE_RADIUS_PADDING_CHUNKS, REPRIORITIZE_IN_FLIGHT_KEEP_RADIUS_CHUNKS);
      this.releaseFarInFlight(players, keepRadius);
      this.priorityQueue.clear();
      this.priorityQueued.clear();

      for (ServerPlayer player : players) {
         ChunkPos pos = player.chunkPosition();
         this.enqueueChunk(pos.x, pos.z);
         this.enqueuePriorityChunk(pos.x, pos.z);
      }
   }

   private void refreshPriorityQueue(List<ServerPlayer> players, int radius) {
      int localRadius = Math.min(PRIORITY_RADIUS_CHUNKS, radius);
      this.prunePriorityQueue(players, localRadius + PRIORITY_RADIUS_CHUNKS);

      for (ServerPlayer player : players) {
         ChunkPos center = player.chunkPosition();

         for (int ring = 0; ring <= localRadius; ring++) {
            this.enqueuePriorityRing(center.x, center.z, ring);
         }
      }
   }

   private void prunePriorityQueue(List<ServerPlayer> players, int keepRadius) {
      if (!this.priorityQueue.isEmpty()) {
         int size = this.priorityQueue.size();
         LongArrayFIFOQueue retained = new LongArrayFIFOQueue(size);
         this.priorityQueued.clear();

         for (int i = 0; i < size; i++) {
            long key = this.priorityQueue.dequeueLong();
            if (isNearAnyPlayer(players, key, keepRadius) && this.priorityQueued.add(key)) {
               retained.enqueue(key);
            }
         }

         this.priorityQueue.clear();

         while (!retained.isEmpty()) {
            this.priorityQueue.enqueue(retained.dequeueLong());
         }
      }
   }

   private void pruneNormalQueue(List<ServerPlayer> players, int keepRadius) {
      if (!this.queue.isEmpty()) {
         int size = this.queue.size();
         LongArrayFIFOQueue retained = new LongArrayFIFOQueue(size);

         for (int i = 0; i < size; i++) {
            long key = this.queue.dequeueLong();
            if (this.queued.contains(key)) {
               if (!isNearAnyPlayer(players, key, keepRadius)) {
                  this.queued.remove(key);
               } else {
                  retained.enqueue(key);
               }
            }
         }

         this.queue.clear();

         while (!retained.isEmpty()) {
            long key = retained.dequeueLong();
            if (this.queued.contains(key)) {
               this.queue.enqueue(key);
            }
         }
      }
   }

   private void releaseFarInFlight(List<ServerPlayer> players, int keepRadius) {
      for (long key : this.inFlight) {
         if (!isNearAnyPlayer(players, key, keepRadius)) {
            this.inFlight.remove(key);
         }
      }
   }

   private static boolean isNearAnyPlayer(List<ServerPlayer> players, long chunkKey, int radius) {
      int chunkX = ChunkPos.getX(chunkKey);
      int chunkZ = ChunkPos.getZ(chunkKey);

      for (ServerPlayer player : players) {
         ChunkPos center = player.chunkPosition();
         if (chebyshevChunkDistance(center.x, center.z, chunkX, chunkZ) <= radius) {
            return true;
         }
      }

      return false;
   }

   private static int chebyshevChunkDistance(int xA, int zA, int xB, int zB) {
      long dx = Math.abs((long)xA - xB);
      long dz = Math.abs((long)zA - zB);
      return (int)Math.min(2147483647L, Math.max(dx, dz));
   }

   private void enqueueRing(int centerX, int centerZ, int ring) {
      if (ring <= 0) {
         this.enqueueChunk(centerX, centerZ);
      } else {
         int minX = centerX - ring;
         int maxX = centerX + ring;
         int minZ = centerZ - ring;
         int maxZ = centerZ + ring;

         for (int x = minX; x <= maxX; x++) {
            this.enqueueChunk(x, minZ);
            if (maxZ != minZ) {
               this.enqueueChunk(x, maxZ);
            }
         }

         for (int z = minZ + 1; z < maxZ; z++) {
            this.enqueueChunk(minX, z);
            if (maxX != minX) {
               this.enqueueChunk(maxX, z);
            }
         }
      }
   }

   private void enqueueChunk(int chunkX, int chunkZ) {
      long key = ChunkPos.asLong(chunkX, chunkZ);
      if (!this.completedChunks.contains(key)) {
         if (this.queued.add(key)) {
            this.queue.enqueue(key);
         }
      }
   }

   private void enqueuePriorityRing(int centerX, int centerZ, int ring) {
      if (ring <= 0) {
         this.enqueuePriorityChunk(centerX, centerZ);
      } else {
         int minX = centerX - ring;
         int maxX = centerX + ring;
         int minZ = centerZ - ring;
         int maxZ = centerZ + ring;

         for (int x = minX; x <= maxX; x++) {
            this.enqueuePriorityChunk(x, minZ);
            if (maxZ != minZ) {
               this.enqueuePriorityChunk(x, maxZ);
            }
         }

         for (int z = minZ + 1; z < maxZ; z++) {
            this.enqueuePriorityChunk(minX, z);
            if (maxX != minX) {
               this.enqueuePriorityChunk(maxX, z);
            }
         }
      }
   }

   private void enqueuePriorityChunk(int chunkX, int chunkZ) {
      long key = ChunkPos.asLong(chunkX, chunkZ);
      if (!this.completedChunks.contains(key)) {
         if (this.priorityQueued.add(key)) {
            this.priorityQueue.enqueue(key);
         }
      }
   }

   private void processQueue(MinecraftServer server, ServerChunkCache source, int chunksPerTick, long averageTickNanos) {
      int launchBudget = this.effectiveLaunchBudget(chunksPerTick, averageTickNanos);
      if (launchBudget > 0) {
         int maxInFlight = Math.min(ABSOLUTE_MAX_IN_FLIGHT, Math.max(launchBudget + 1, launchBudget * IN_FLIGHT_MULTIPLIER));
         int launched = 0;

         while (launched < launchBudget) {
            if (this.inFlight.size() >= maxInFlight) {
               return;
            }

            Long polled = this.pollNextChunk();
            if (polled == null) {
               return;
            }

            long key = polled;
            if (!this.inFlight.contains(key) && this.inFlight.add(key)) {
               int chunkX = ChunkPos.getX(key);
               int chunkZ = ChunkPos.getZ(key);
               source.getChunkFuture(chunkX, chunkZ, ChunkStatus.FULL, true).whenComplete((result, error) -> {
                  this.inFlight.remove(key);
                  if (error == null && result != null && result.isSuccess()) {
                     result.ifSuccess(chunk -> this.ingestIfLevelChunk(server, chunk, key));
                  }
               });
               launched++;
            }
         }
      }
   }

   private int effectiveLaunchBudget(int chunksPerTick, long averageTickNanos) {
      int base = Math.max(1, chunksPerTick);
      if (averageTickNanos >= HARD_OVERLOAD_TICK_NANOS) {
         return 0;
      } else if (averageTickNanos >= SOFT_OVERLOAD_TICK_NANOS) {
         return 1;
      } else if (averageTickNanos >= TICK_NANOS) {
         return Math.max(1, base / 2);
      } else {
         return averageTickNanos < TICK_NANOS - 10000000L ? Math.min(base + Math.max(1, base / 2), ABSOLUTE_MAX_IN_FLIGHT) : base;
      }
   }

   
   private Long pollNextChunk() {
      if (!this.priorityQueue.isEmpty()) {
         long key = this.priorityQueue.dequeueLong();
         this.priorityQueued.remove(key);
         this.queued.remove(key);
         return key;
      } else {
         while (!this.queue.isEmpty()) {
            long key = this.queue.dequeueLong();
            if (this.queued.remove(key)) {
               return key;
            }
         }

         return null;
      }
   }

   private void ingestIfLevelChunk(MinecraftServer server, ChunkAccess chunk, long chunkKey) {
      if (chunk instanceof LevelChunk levelChunk) {
         this.recordCompletedChunk(chunkKey);
         VoxyBridge.tryIngestChunkAsync(server, levelChunk);
      }
   }

   private void recordCompletedChunk(long chunkKey) {
      if (this.completedChunks.addAndMoveToLast(chunkKey) && this.completedChunks.size() > COMPLETED_CACHE_MAX) {
         this.completedChunks.removeFirstLong();
      }
   }

   private void clearQueueAndPlayerState() {
      this.queue.clear();
      this.queued.clear();
      this.priorityQueue.clear();
      this.priorityQueued.clear();
      this.playerStates.clear();
   }

   private static final class PlayerPregenState {
      private int centerX = Integer.MIN_VALUE;
      private int centerZ = Integer.MIN_VALUE;
      private int anchorX = Integer.MIN_VALUE;
      private int anchorZ = Integer.MIN_VALUE;
      private int nextRing;
   }
}
