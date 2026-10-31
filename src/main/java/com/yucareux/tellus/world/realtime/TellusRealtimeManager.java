package com.yucareux.tellus.world.realtime;

import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.network.TellusWeatherPayload;
import com.yucareux.tellus.platform.TellusPlatform;
import com.yucareux.tellus.world.data.source.NominatimGeocoder;
import com.yucareux.tellus.world.data.source.OpenMeteoClient;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;

public final class TellusRealtimeManager {
   private static final long WEATHER_REFRESH_MS = 600000L;
   private static final long WEATHER_RETRY_MS = 60000L;
   private static final long REPORT_COOLDOWN_MS = 10000L;
   private static final int REPORT_QUEUE_CAPACITY = 16;
   private static final long TIMEZONE_REFRESH_MS = 21600000L;
   private static final long TIME_APPLY_TICK_INTERVAL = 20L;
   private static final long SNOW_QUEUE_REFRESH_TICKS = 100L;
   private static final int SNOW_CHUNKS_PER_TICK = 8;
   private static final int SNOW_MAX_RADIUS = 10;
   private static final int GRID_RADIUS = 1;
   private static final int GRID_SIZE = 3;
   private static final int GRID_POINTS = 9;
   private static final boolean NTP_ENABLED = Boolean.getBoolean("tellus.realtime.ntp");
   private static final long NTP_REFRESH_MS = 43200000L;
   private static final int NTP_TIMEOUT_MS = 2000;
   private static final int NTP_PORT = 123;
   private static final long NTP_EPOCH_OFFSET_SECONDS = 2208988800L;
   private static final long MAX_NTP_OFFSET_MS = 86400000L;
   private static final String[] NTP_SERVERS = new String[]{"time.google.com", "pool.ntp.org", "time.cloudflare.com"};
   private final OpenMeteoClient client = new OpenMeteoClient();
   private final NominatimGeocoder geocoder = new NominatimGeocoder("en");
   private final ThreadFactory threadFactory;
   private volatile ExecutorService executor;
   private volatile ExecutorService reportExecutor;
   private final Set<UUID> reportRequestsInFlight = ConcurrentHashMap.newKeySet();
   private final Map<UUID, Long> lastReportRequestMs = new ConcurrentHashMap<>();
   private final AtomicBoolean requestInFlight = new AtomicBoolean(false);
   private final AtomicBoolean pendingInitialSnowPass = new AtomicBoolean(false);
   private final LongArrayFIFOQueue snowQueue = new LongArrayFIFOQueue();
   private final LongOpenHashSet snowQueued = new LongOpenHashSet();
   private long lastWeatherUpdateMs;
   private long lastWeatherAttemptMs;
   private long lastTimeZoneUpdateMs;
   private long lastTimeApplyTick;
   private long lastSnowQueueTick;
   private boolean timeZoneReady;
   private TellusRealtimeManager.GridAnchor lastAnchor;
   private TellusRealtimeManager.GridAnchor lastWeatherAttemptAnchor;
   private TellusRealtimeManager.GridAnchor lastTimeZoneAnchor;
   private int utcOffsetSeconds;
   private volatile long ntpOffsetMillis;
   private volatile long lastNtpSyncMs;
   private volatile Boolean realtimeTimeOverride;
   private volatile Boolean realtimeWeatherOverride;
   private volatile TellusRealtimeManager.WeatherSnapshot lastWeatherSnapshot;
   private volatile ZoneId timeZone;
   private volatile String timeZoneId;
   private final AtomicBoolean ntpRequestInFlight = new AtomicBoolean(false);
   private boolean cachedDaylightCycle;
   private boolean cachedWeatherCycle;
   private int cachedSleepPercentage = -1;
   private boolean timeRulesCaptured;
   private boolean weatherRulesCaptured;

   public TellusRealtimeManager() {
      this.threadFactory = runnable -> {
         Thread thread = new Thread(runnable, "tellus-realtime");
         thread.setDaemon(true);
         return thread;
      };
      this.executor = this.createExecutor();
   }

   public void shutdown() {
      ExecutorService exec = this.executor;
      if (exec != null) {
         exec.shutdownNow();
      }
      ExecutorService reports = this.reportExecutor;
      if (reports != null) {
         reports.shutdownNow();
      }
   }

   public void onServerStopping(MinecraftServer server) {
      ServerLevel level = server.getLevel(Level.OVERWORLD);
      if (level != null) {
         this.clearRealtimeState(server, level, true);
      } else {
         this.resetRuntimeState();
      }

      this.clearOverrides();
      ExecutorService exec = this.executor;
      this.executor = null;
      if (exec != null) {
         exec.shutdownNow();
      }
      ExecutorService reports = this.reportExecutor;
      this.reportExecutor = null;
      if (reports != null) {
         reports.shutdownNow();
      }

      this.requestInFlight.set(false);
      this.ntpRequestInFlight.set(false);
      this.reportRequestsInFlight.clear();
      this.lastReportRequestMs.clear();
   }

   public boolean hasTimeOffset() {
      return this.timeZoneReady;
   }

   public int currentUtcOffsetSeconds() {
      ZoneId zone = this.timeZone;
      return zone != null && this.timeZoneReady ? zone.getRules().getOffset(this.currentInstant()).getTotalSeconds() : this.utcOffsetSeconds;
   }

   public Instant currentInstant() {
      return Instant.ofEpochMilli(this.currentTimeMillis());
   }

   public ZoneId currentTimeZone() {
      return this.timeZone;
   }

   public String currentTimeZoneId() {
      return this.timeZoneId;
   }

   public void setRealtimeTimeOverride(boolean enabled) {
      this.realtimeTimeOverride = enabled;
   }

   public void setRealtimeWeatherOverride(boolean enabled) {
      this.realtimeWeatherOverride = enabled;
      if (enabled) {
         this.lastWeatherAttemptMs = 0L;
      }
   }

   public void clearOverrides() {
      this.realtimeTimeOverride = null;
      this.realtimeWeatherOverride = null;
   }

   public boolean isRealtimeTimeEnabled(EarthGeneratorSettings settings) {
      Boolean override = this.realtimeTimeOverride;
      return override != null ? override : settings.realtimeTime();
   }

   public boolean isRealtimeWeatherEnabled(EarthGeneratorSettings settings) {
      Boolean override = this.realtimeWeatherOverride;
      return override != null ? override : settings.realtimeWeather();
   }

   public TellusRealtimeManager.WeatherSnapshot lastWeatherSnapshot() {
      return this.lastWeatherSnapshot;
   }

   public void onPlayerJoin(MinecraftServer server, ServerPlayer player) {
      TellusPlatform.sendWeatherPayload(player, this.currentWeatherPayload());
   }

   public void onServerTick(MinecraftServer server) {
      ServerLevel level = server.getLevel(Level.OVERWORLD);
      if (level == null) {
         this.resetRuntimeState();
         return;
      }

      long tickCount = server.getTickCount();
      if (tickCount < this.lastTimeApplyTick) {
         this.lastTimeApplyTick = 0L;
      }

      if (tickCount < this.lastSnowQueueTick) {
         this.lastSnowQueueTick = 0L;
      }

      if (!(level.getChunkSource().getGenerator() instanceof EarthChunkGenerator earthGenerator)) {
         this.clearRealtimeState(server, level, true);
         return;
      }

      EarthGeneratorSettings settings = earthGenerator.settings();
      boolean enableTime = this.isRealtimeTimeEnabled(settings);
      boolean enableWeather = this.isRealtimeWeatherEnabled(settings);

      if (!enableWeather) {
         boolean weatherWasActive = this.hasRealtimeWeatherState();
         TellusRealtimeState.clearRealtimeWeather();
         if (weatherWasActive) {
            this.sendWeatherPayload(server, false, TellusRealtimeState.PrecipitationMode.CLEAR, false, SnowGrid.empty());
         }
      }

      TellusRealtimeManager.GridAnchor anchor = TellusRealtimeManager.GridAnchor.resolve(server, computeGridSpacing(settings.worldScale()));
      if (anchor != null) {
         BlockPos samplePos = new BlockPos(anchor.centerX(), 0, anchor.centerZ());
         boolean movedAnchor = this.lastAnchor == null || !this.lastAnchor.equals(anchor);
         boolean movedTimeZoneAnchor = this.lastTimeZoneAnchor == null || !this.lastTimeZoneAnchor.equals(anchor);
         if (movedAnchor) {
            boolean notifyPlayers = !TellusRealtimeState.temperatureGrid().isEmpty() || enableWeather && this.hasRealtimeWeatherState();
            TellusRealtimeState.clearTemperature();
            if (enableWeather) {
               TellusRealtimeState.clearRealtimeWeather();
            }
            if (notifyPlayers) {
               this.sendWeatherPayload(
                  server,
                  TellusRealtimeState.isWeatherEnabled(),
                  TellusRealtimeState.precipitationMode(),
                  TellusRealtimeState.isHistoricalSnowEnabled(),
                  TellusRealtimeState.snowGrid()
               );
            }
         }

         if (enableTime) {
            this.applyTimeRules(level, server);
            this.maybeRefreshNtp(System.currentTimeMillis());
            this.applyRealtimeTime(level, server, earthGenerator, samplePos);
         } else {
            this.restoreTimeRules(level, server);
            this.timeZoneReady = false;
            this.timeZone = null;
            this.timeZoneId = null;
         }

         long now = System.currentTimeMillis();
         if (enableWeather) {
            this.applyWeatherRules(level, server);
            this.applyRealtimeWeather(level);
         } else {
            this.restoreWeatherRules(level, server);
         }

         boolean weatherPending = !TellusRealtimeState.hasRealWorldTemperature(samplePos)
            || enableWeather && !TellusRealtimeState.isWeatherEnabled();
         boolean retryReady = !anchor.equals(this.lastWeatherAttemptAnchor)
            || this.lastWeatherAttemptMs == 0L
            || now - this.lastWeatherAttemptMs >= WEATHER_RETRY_MS;
         boolean shouldUpdateWeather = retryReady && (weatherPending || movedAnchor || now - this.lastWeatherUpdateMs >= WEATHER_REFRESH_MS);
         boolean shouldUpdateTimeZone = enableTime && (movedTimeZoneAnchor || now - this.lastTimeZoneUpdateMs >= TIMEZONE_REFRESH_MS);
         if (shouldUpdateWeather || retryReady && shouldUpdateTimeZone) {
            this.requestUpdate(server, earthGenerator, anchor, enableWeather, false, enableTime, now);
         }

         if (!enableWeather || TellusRealtimeState.precipitationMode() != TellusRealtimeState.PrecipitationMode.SNOW) {
            this.clearSnowQueue();
         } else {
            this.tickSnowPlacement(server, level, earthGenerator);
         }
      } else {
         TellusRealtimeState.updateWeatherState(false, TellusRealtimeState.PrecipitationMode.CLEAR, false, SnowGrid.empty());
      }
   }

   public TellusRealtimeManager.WeatherReportRequestResult requestWeatherReport(
      MinecraftServer server,
      UUID requesterId,
      EarthChunkGenerator generator,
      BlockPos pos,
      Consumer<TellusRealtimeManager.WeatherReport> callback
   ) {
      Objects.requireNonNull(server, "server");
      Objects.requireNonNull(requesterId, "requesterId");
      Objects.requireNonNull(generator, "generator");
      Objects.requireNonNull(pos, "pos");
      Objects.requireNonNull(callback, "callback");
      double latitude = Mth.clamp(generator.latitudeFromBlock(pos.getZ()), -85.05112878, 85.05112878);
      double longitude = Mth.clamp(generator.longitudeFromBlock(pos.getX()), -180.0, 180.0);
      long now = System.currentTimeMillis();
      Long lastRequest = this.lastReportRequestMs.get(requesterId);
      if (this.reportRequestsInFlight.contains(requesterId) || lastRequest != null && now - lastRequest < REPORT_COOLDOWN_MS) {
         return TellusRealtimeManager.WeatherReportRequestResult.RATE_LIMITED;
      }
      if (!this.reportRequestsInFlight.add(requesterId)) {
         return TellusRealtimeManager.WeatherReportRequestResult.RATE_LIMITED;
      }

      this.lastReportRequestMs.put(requesterId, now);
      ExecutorService exec = this.ensureReportExecutor();
      if (exec == null) {
         this.reportRequestsInFlight.remove(requesterId);
         return TellusRealtimeManager.WeatherReportRequestResult.UNAVAILABLE;
      }

      try {
         exec.execute(() -> {
            OpenMeteoClient.WeatherPointData weather = null;
            NominatimGeocoder.Location location = null;
            try {
               weather = this.client.fetch(latitude, longitude);
            } catch (Exception error) {
               Tellus.LOGGER.warn("Failed to fetch local weather report: {}", error.getMessage());
               Tellus.LOGGER.debug("Local weather report fetch failure", error);
            }

            try {
               location = this.geocoder.reverse(latitude, longitude);
            } catch (Exception error) {
               Tellus.LOGGER.warn("Failed to reverse geocode local weather report: {}", error.getMessage());
               Tellus.LOGGER.debug("Local weather reverse geocoding failure", error);
            }

            OpenMeteoClient.WeatherPointData resolvedWeather = weather;
            NominatimGeocoder.Location resolvedLocation = location;
            server.execute(
               () -> {
                  this.reportRequestsInFlight.remove(requesterId);
                  callback.accept(
                  new TellusRealtimeManager.WeatherReport(
                     latitude,
                     longitude,
                     resolvedWeather == null ? Float.NaN : resolvedWeather.temperatureC(),
                     resolvedWeather == null ? null : resolvedWeather.timeZoneId(),
                     resolvedLocation == null ? "" : resolvedLocation.displayName()
                     )
                  );
               }
            );
         });
         return TellusRealtimeManager.WeatherReportRequestResult.QUEUED;
      } catch (RejectedExecutionException error) {
         this.reportRequestsInFlight.remove(requesterId);
         this.lastReportRequestMs.remove(requesterId, now);
         Tellus.LOGGER.debug("Realtime weather executor rejected local report task.", error);
         return TellusRealtimeManager.WeatherReportRequestResult.UNAVAILABLE;
      }
   }

   private void requestUpdate(
      MinecraftServer server,
      EarthChunkGenerator generator,
      TellusRealtimeManager.GridAnchor anchor,
      boolean includeWeather,
      boolean includeSnow,
      boolean includeTimeZone,
      long now
   ) {
      if (this.requestInFlight.compareAndSet(false, true)) {
         this.lastWeatherAttemptMs = now;
         this.lastWeatherAttemptAnchor = anchor;
         List<BlockPos> samplePoints = new ArrayList<>(GRID_POINTS);
         for (int dz = -GRID_RADIUS; dz <= GRID_RADIUS; dz++) {
            for (int dx = -GRID_RADIUS; dx <= GRID_RADIUS; dx++) {
               int x = anchor.centerX() + dx * anchor.spacingBlocks();
               int z = anchor.centerZ() + dz * anchor.spacingBlocks();
               samplePoints.add(new BlockPos(x, 0, z));
            }
         }

         ExecutorService exec = this.ensureExecutor();
         if (exec == null) {
            this.requestInFlight.set(false);
         } else {
            try {
               exec.execute(() -> {
                  try {
                     OpenMeteoClient.WeatherPointData[] points = new OpenMeteoClient.WeatherPointData[samplePoints.size()];

                     for (int i = 0; i < samplePoints.size(); i++) {
                        BlockPos pos = samplePoints.get(i);
                        double lat = Mth.clamp(generator.latitudeFromBlock(pos.getZ()), -85.05112878, 85.05112878);
                        double lon = Mth.clamp(generator.longitudeFromBlock(pos.getX()), -180.0, 180.0);
                        points[i] = this.client.fetch(lat, lon);
                     }

                     server.execute(() -> this.applyUpdate(server, anchor, includeWeather, includeSnow, includeTimeZone, points, now));
                  } catch (Exception var20) {
                     Tellus.LOGGER.warn("Failed to fetch real-time weather data: {}", var20.getMessage());
                     Tellus.LOGGER.debug("Real-time weather fetch failure", var20);
                  } finally {
                     this.requestInFlight.set(false);
                  }
               });
            } catch (RejectedExecutionException var14) {
               this.requestInFlight.set(false);
               Tellus.LOGGER.debug("Realtime weather executor rejected task (server stopping or restarting).", var14);
            }
         }
      }
   }

   private ExecutorService createExecutor() {
      return Executors.newSingleThreadExecutor(this.threadFactory);
   }

   private ExecutorService createReportExecutor() {
      return new ThreadPoolExecutor(
         2,
         2,
         0L,
         TimeUnit.MILLISECONDS,
         new ArrayBlockingQueue<>(REPORT_QUEUE_CAPACITY),
         runnable -> {
            Thread thread = new Thread(runnable, "tellus-weather-report");
            thread.setDaemon(true);
            return thread;
         },
         new ThreadPoolExecutor.AbortPolicy()
      );
   }

   private ExecutorService ensureExecutor() {
      ExecutorService exec = this.executor;
      if (exec != null && !exec.isShutdown() && !exec.isTerminated()) {
         return exec;
      } else {
         synchronized (this) {
            exec = this.executor;
            if (exec == null || exec.isShutdown() || exec.isTerminated()) {
               exec = this.createExecutor();
               this.executor = exec;
               this.requestInFlight.set(false);
            }

            return exec;
         }
      }
   }

   private ExecutorService ensureReportExecutor() {
      ExecutorService exec = this.reportExecutor;
      if (exec != null && !exec.isShutdown() && !exec.isTerminated()) {
         return exec;
      }

      synchronized (this) {
         exec = this.reportExecutor;
         if (exec == null || exec.isShutdown() || exec.isTerminated()) {
            exec = this.createReportExecutor();
            this.reportExecutor = exec;
         }
         return exec;
      }
   }

   private long currentTimeMillis() {
      long now = System.currentTimeMillis();
      return !NTP_ENABLED ? now : now + this.ntpOffsetMillis;
   }

   private void maybeRefreshNtp(long nowMs) {
      if (NTP_ENABLED) {
         if (this.lastNtpSyncMs == 0L || nowMs - this.lastNtpSyncMs >= NTP_REFRESH_MS) {
            if (this.ntpRequestInFlight.compareAndSet(false, true)) {
               ExecutorService exec = this.ensureExecutor();
               if (exec == null) {
                  this.ntpRequestInFlight.set(false);
               } else {
                  exec.execute(() -> {
                     try {
                        Long offset = null;

                        for (String host : NTP_SERVERS) {
                           try {
                              offset = queryNtpOffsetMillis(host);
                              break;
                           } catch (Exception var10) {
                              Tellus.LOGGER.debug("Failed NTP sync with {}", host, var10);
                           }
                        }

                        if (offset != null) {
                           this.ntpOffsetMillis = offset;
                           this.lastNtpSyncMs = System.currentTimeMillis();
                        }
                     } finally {
                        this.ntpRequestInFlight.set(false);
                     }
                  });
               }
            }
         }
      }
   }

   private static long queryNtpOffsetMillis(String host) throws Exception {
      byte[] buffer = new byte[48];
      buffer[0] = 35;
      long sendTime = System.currentTimeMillis();
      long sendNanos = System.nanoTime();
      writeNtpTimestamp(buffer, 40, sendTime);
      byte[] requestTimestamp = new byte[8];
      System.arraycopy(buffer, 40, requestTimestamp, 0, requestTimestamp.length);
      InetAddress address = InetAddress.getByName(host);

      try (DatagramSocket socket = new DatagramSocket()) {
         socket.setSoTimeout(NTP_TIMEOUT_MS);
         socket.connect(address, NTP_PORT);
         DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
         socket.send(packet);
         DatagramPacket response = new DatagramPacket(buffer, buffer.length);
         socket.receive(response);
         validateNtpResponse(buffer, response.getLength(), requestTimestamp);
      }

      long roundTrip = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sendNanos);
      long serverTransmitTime = readNtpTimestamp(buffer, 40);
      long offset = serverTransmitTime - (sendTime + roundTrip / 2L);
      if (Math.abs(offset) > MAX_NTP_OFFSET_MS) {
         throw new IOException("NTP response offset exceeds the 24-hour safety limit");
      }
      return offset;
   }

   private static void validateNtpResponse(byte[] buffer, int length, byte[] requestTimestamp) throws IOException {
      int leapIndicator = buffer[0] >>> 6 & 3;
      int version = buffer[0] >>> 3 & 7;
      int mode = buffer[0] & 7;
      int stratum = buffer[1] & 255;
      if (length < 48 || leapIndicator == 3 || version < 3 || mode != 4 || stratum == 0 || stratum > 15) {
         throw new IOException("Invalid NTP server response");
      }
      for (int index = 0; index < requestTimestamp.length; index++) {
         if (buffer[24 + index] != requestTimestamp[index]) {
            throw new IOException("NTP response does not match the request timestamp");
         }
      }
   }

   private static void writeNtpTimestamp(byte[] buffer, int offset, long unixMillis) {
      long seconds = Math.floorDiv(unixMillis, 1000L) + NTP_EPOCH_OFFSET_SECONDS;
      long fraction = Math.floorMod(unixMillis, 1000L) * 4294967296L / 1000L;
      for (int index = 3; index >= 0; index--) {
         buffer[offset + index] = (byte)seconds;
         seconds >>>= 8;
         buffer[offset + 4 + index] = (byte)fraction;
         fraction >>>= 8;
      }
   }

   private static long readNtpTimestamp(byte[] buffer, int offset) {
      long seconds = 0L;
      long fraction = 0L;

      for (int i = 0; i < 4; i++) {
         seconds = seconds << 8 | buffer[offset + i] & 255L;
      }

      for (int i = 4; i < 8; i++) {
         fraction = fraction << 8 | buffer[offset + i] & 255L;
      }

      long epochSeconds = seconds - NTP_EPOCH_OFFSET_SECONDS;
      return epochSeconds * 1000L + fraction * 1000L / 4294967296L;
   }

   private void updateTimeZone(OpenMeteoClient.WeatherPointData centerPoint, TellusRealtimeManager.GridAnchor anchor, long now) {
      ZoneId zone = parseZoneId(centerPoint.timeZoneId());
      if (zone != null) {
         this.timeZone = zone;
         this.timeZoneId = zone.getId();
         this.timeZoneReady = true;
      } else {
         this.timeZone = null;
         this.timeZoneId = null;
         this.timeZoneReady = false;
      }

      this.utcOffsetSeconds = centerPoint.utcOffsetSeconds();
      this.lastTimeZoneAnchor = anchor;
      this.lastTimeZoneUpdateMs = now;
   }

   private static ZoneId parseZoneId(String zoneId) {
      if (zoneId != null && !zoneId.isBlank()) {
         try {
            return ZoneId.of(zoneId);
         } catch (DateTimeException var2) {
            return null;
         }
      } else {
         return null;
      }
   }

   private ZoneId resolveTimeZone(EarthChunkGenerator generator, BlockPos pos) {
      ZoneId zone = this.timeZone;
      if (zone != null && this.timeZoneReady) {
         return zone;
      } else if (this.lastTimeZoneUpdateMs > 0L) {
         return ZoneOffset.ofTotalSeconds(this.utcOffsetSeconds);
      } else {
         int offsetSeconds = approximateUtcOffsetSeconds(generator, pos);
         return ZoneOffset.ofTotalSeconds(offsetSeconds);
      }
   }

   private void tickSnowPlacement(MinecraftServer server, ServerLevel level, EarthChunkGenerator generator) {
      long tick = server.getTickCount();
      if (tick - this.lastSnowQueueTick >= SNOW_QUEUE_REFRESH_TICKS || this.snowQueue.isEmpty()) {
         this.queueSnowChunks(server);
         this.lastSnowQueueTick = tick;
      }

      this.processSnowQueue(level, generator, SNOW_CHUNKS_PER_TICK);
   }

   private void queueSnowChunks(MinecraftServer server) {
      int radius = Math.min(server.getPlayerList().getViewDistance(), SNOW_MAX_RADIUS);
      if (radius > 0) {
         for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ChunkPos center = player.chunkPosition();
            int baseX = MinecraftVersionCompat.chunkX(center);
            int baseZ = MinecraftVersionCompat.chunkZ(center);

            for (int dz = -radius; dz <= radius; dz++) {
               for (int dx = -radius; dx <= radius; dx++) {
                  long key = MinecraftVersionCompat.packChunkPos(baseX + dx, baseZ + dz);
                  if (this.snowQueued.add(key)) {
                     this.snowQueue.enqueue(key);
                  }
               }
            }
         }
      }
   }

   private void processSnowQueue(ServerLevel level, EarthChunkGenerator generator, int maxChunks) {
      ChunkSource source = level.getChunkSource();
      int processed = 0;

      while (processed < maxChunks && !this.snowQueue.isEmpty()) {
         long key = this.snowQueue.dequeueLong();
         this.snowQueued.remove(key);
         int chunkX = ChunkPos.getX(key);
         int chunkZ = ChunkPos.getZ(key);
         LevelChunk chunk = source.getChunkNow(chunkX, chunkZ);
         if (chunk != null) {
            generator.applyRealtimeSnowCover(level, chunk);
            processed++;
         }
      }
   }

   private void clearSnowQueue() {
      this.snowQueue.clear();
      this.snowQueued.clear();
   }

   private void applyUpdate(
      MinecraftServer server,
      TellusRealtimeManager.GridAnchor anchor,
      boolean includeWeather,
      boolean includeSnow,
      boolean includeTimeZone,
      OpenMeteoClient.WeatherPointData[] points,
      long now
   ) {
      if (points.length != 0) {
         int centerIndex = GRID_POINTS / 2;
         OpenMeteoClient.WeatherPointData centerPoint = points[Math.min(centerIndex, points.length - 1)];
         this.lastAnchor = anchor;
         this.lastWeatherSnapshot = new TellusRealtimeManager.WeatherSnapshot(
            centerPoint.latitude(), centerPoint.longitude(), centerPoint.utcOffsetSeconds(), centerPoint.timeZoneId(), centerPoint.temperatureC(), now
         );
         this.lastWeatherUpdateMs = now;

         if (includeTimeZone) {
            this.updateTimeZone(centerPoint, anchor, now);
         }

         TellusRealtimeState.PrecipitationMode mode = TellusRealtimeState.PrecipitationMode.CLEAR;
         if (includeWeather) {
            mode = resolvePrecipitation(centerPoint);
         }

         SnowGrid grid = SnowGrid.empty();
         if (includeSnow) {
            float[] snowIndex = new float[GRID_POINTS];

            for (int i = 0; i < snowIndex.length && i < points.length; i++) {
               snowIndex[i] = points[i].snowIndex();
            }

            grid = new SnowGrid(anchor.centerX(), anchor.centerZ(), anchor.spacingBlocks(), snowIndex);
         }

         float[] temperatures = new float[GRID_POINTS];
         float[] recentMeanTemperatures = new float[GRID_POINTS];
         for (int i = 0; i < temperatures.length && i < points.length; i++) {
            temperatures[i] = points[i].temperatureC();
            recentMeanTemperatures[i] = points[i].recentMeanTemperatureC();
         }
         TemperatureGrid temperatureGrid = new TemperatureGrid(
            anchor.centerX(), anchor.centerZ(), anchor.spacingBlocks(), temperatures, now
         );
         TellusRealtimeState.updateWeatherState(includeWeather, mode, includeSnow, grid, temperatureGrid);
         TellusRealtimeState.updateRecentTemperature(
            new TemperatureGrid(anchor.centerX(), anchor.centerZ(), anchor.spacingBlocks(), recentMeanTemperatures, now)
         );
         this.sendWeatherPayload(server, includeWeather, mode, includeSnow, grid);
         if (includeSnow && this.pendingInitialSnowPass.getAndSet(false)) {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            if (level == null) {
               return;
            }

            if (!(level.getChunkSource().getGenerator() instanceof EarthChunkGenerator earthGenerator)) {
               return;
            }

            this.clearSnowQueue();
            this.queueSnowChunks(server);
            this.processSnowQueue(level, earthGenerator, Integer.MAX_VALUE);
         }
      }
   }

   private void sendWeatherPayload(
      MinecraftServer server, boolean weatherEnabled, TellusRealtimeState.PrecipitationMode mode, boolean historicalSnow, SnowGrid grid
   ) {
      TellusWeatherPayload payload = createWeatherPayload(weatherEnabled, mode, historicalSnow, grid);

      for (ServerPlayer rawPlayer : server.getPlayerList().getPlayers()) {
         ServerPlayer player = Objects.requireNonNull(rawPlayer, "player");
         TellusPlatform.sendWeatherPayload(player, payload);
      }
   }

   private TellusWeatherPayload currentWeatherPayload() {
      return createWeatherPayload(
         TellusRealtimeState.isWeatherEnabled(),
         TellusRealtimeState.precipitationMode(),
         TellusRealtimeState.isHistoricalSnowEnabled(),
         TellusRealtimeState.snowGrid()
      );
   }

   private static TellusWeatherPayload createWeatherPayload(
      boolean weatherEnabled, TellusRealtimeState.PrecipitationMode mode, boolean historicalSnow, SnowGrid grid
   ) {
      TemperatureGrid temperatureGrid = TellusRealtimeState.temperatureGrid();
      int centerX = temperatureGrid.isEmpty() ? grid.centerX() : temperatureGrid.centerX();
      int centerZ = temperatureGrid.isEmpty() ? grid.centerZ() : temperatureGrid.centerZ();
      int spacingBlocks = temperatureGrid.isEmpty() ? grid.spacingBlocks() : temperatureGrid.spacingBlocks();
      return new TellusWeatherPayload(
         weatherEnabled,
         mode,
         historicalSnow,
         centerX,
         centerZ,
         spacingBlocks,
         temperatureGrid.isEmpty() ? 1200001L : Math.max(0L, System.currentTimeMillis() - temperatureGrid.updatedAtMs()),
         temperatureGrid.samples(),
         grid.isEmpty() ? new float[GRID_POINTS] : gridSample(grid)
      );
   }

   private static float[] gridSample(SnowGrid grid) {
      float[] snowIndex = new float[GRID_POINTS];

      for (int i = 0; i < GRID_POINTS; i++) {
         snowIndex[i] = grid.isEmpty() ? 0.0F : gridSampleAtIndex(grid, i);
      }

      return snowIndex;
   }

   private static float gridSampleAtIndex(SnowGrid grid, int index) {
      int gx = index % GRID_SIZE;
      int gz = index / GRID_SIZE;
      int x = grid.centerX() + (gx - GRID_RADIUS) * grid.spacingBlocks();
      int z = grid.centerZ() + (gz - GRID_RADIUS) * grid.spacingBlocks();
      return grid.sample(x, z);
   }

   private static TellusRealtimeState.PrecipitationMode resolvePrecipitation(OpenMeteoClient.WeatherPointData data) {
      int code = data.weatherCode();
      float temp = data.temperatureC();
      float precipitation = data.precipitationMm();
      if (code >= 95) {
         return TellusRealtimeState.PrecipitationMode.THUNDER;
      } else if (!isSnowCode(code) && (!(temp <= 0.0F) || !(precipitation > 0.0F))) {
         return !(precipitation > 0.0F) && !isRainCode(code) ? TellusRealtimeState.PrecipitationMode.CLEAR : TellusRealtimeState.PrecipitationMode.RAIN;
      } else {
         return TellusRealtimeState.PrecipitationMode.SNOW;
      }
   }

   private static boolean isSnowCode(int code) {
      return code >= 71 && code <= 77 || code == 85 || code == 86;
   }

   private static boolean isRainCode(int code) {
      return code >= 51 && code <= 67 || code >= 80 && code <= 82;
   }

   private void applyRealtimeTime(ServerLevel level, MinecraftServer server, EarthChunkGenerator generator, BlockPos samplePos) {
      long tickCount = server.getTickCount();
      if (tickCount - this.lastTimeApplyTick >= TIME_APPLY_TICK_INTERVAL) {
         this.lastTimeApplyTick = tickCount;
         Instant now = this.currentInstant();
         ZoneId zone = this.resolveTimeZone(generator, samplePos);
         ZonedDateTime local = ZonedDateTime.ofInstant(now, zone);
         int daySeconds = local.toLocalTime().toSecondOfDay();
         double hours = daySeconds / 3600.0;
         int tickOfDay = (int)Math.floor((hours - 6.0 + 24.0) % 24.0 * 1000.0);
         long dayBase = RealtimeVersionCompat.dayTimeTicks(level) / 24000L * 24000L;
         RealtimeVersionCompat.setDayTimeTicks(level, dayBase + tickOfDay);
         this.utcOffsetSeconds = local.getOffset().getTotalSeconds();
      }
   }

   private void applyRealtimeWeather(ServerLevel level) {
      TellusRealtimeState.PrecipitationMode mode = TellusRealtimeState.precipitationMode();
      boolean raining = mode == TellusRealtimeState.PrecipitationMode.RAIN
         || mode == TellusRealtimeState.PrecipitationMode.SNOW
         || mode == TellusRealtimeState.PrecipitationMode.THUNDER;
      boolean thundering = mode == TellusRealtimeState.PrecipitationMode.THUNDER;
      RealtimeVersionCompat.applyWeather(level, raining, thundering);
   }

   private void applyTimeRules(ServerLevel level, MinecraftServer server) {
      Boolean daylight = RealtimeVersionCompat.daylightCycle(level);
      Integer sleepingPercent = RealtimeVersionCompat.sleepingPercentage(level);
      if (!this.timeRulesCaptured) {
         this.cachedDaylightCycle = daylight != null && daylight;
         this.cachedSleepPercentage = sleepingPercent == null ? -1 : sleepingPercent;
         this.timeRulesCaptured = true;
      }

      if (daylight == null || daylight) {
         RealtimeVersionCompat.setDaylightCycle(level, server, false);
      }

      if (sleepingPercent == null || sleepingPercent != 101) {
         RealtimeVersionCompat.setSleepingPercentage(level, server, 101);
      }
   }

   private void applyWeatherRules(ServerLevel level, MinecraftServer server) {
      Boolean weatherCycle = RealtimeVersionCompat.weatherCycle(level);
      if (!this.weatherRulesCaptured) {
         this.cachedWeatherCycle = weatherCycle != null && weatherCycle;
         this.weatherRulesCaptured = true;
      }

      if (weatherCycle == null || weatherCycle) {
         RealtimeVersionCompat.setWeatherCycle(level, server, false);
      }
   }

   private void restoreRules(ServerLevel level, MinecraftServer server) {
      this.restoreTimeRules(level, server);
      this.restoreWeatherRules(level, server);
   }

   private void restoreTimeRules(ServerLevel level, MinecraftServer server) {
      if (this.timeRulesCaptured) {
         Boolean daylight = RealtimeVersionCompat.daylightCycle(level);
         Integer sleepingPercent = RealtimeVersionCompat.sleepingPercentage(level);
         if (daylight == null || daylight != this.cachedDaylightCycle) {
            RealtimeVersionCompat.setDaylightCycle(level, server, this.cachedDaylightCycle);
         }

         if (this.cachedSleepPercentage >= 0 && (sleepingPercent == null || sleepingPercent != this.cachedSleepPercentage)) {
            RealtimeVersionCompat.setSleepingPercentage(level, server, this.cachedSleepPercentage);
         }

         this.timeRulesCaptured = false;
      }
   }

   private void restoreWeatherRules(ServerLevel level, MinecraftServer server) {
      if (this.weatherRulesCaptured) {
         Boolean weatherCycle = RealtimeVersionCompat.weatherCycle(level);
         if (weatherCycle == null || weatherCycle != this.cachedWeatherCycle) {
            RealtimeVersionCompat.setWeatherCycle(level, server, this.cachedWeatherCycle);
         }

         this.weatherRulesCaptured = false;
      }
   }

   private void clearRealtimeState(MinecraftServer server, ServerLevel level, boolean notifyPlayers) {
      boolean weatherWasActive = this.hasWeatherStateForClients();
      this.restoreRules(level, server);
      this.resetRuntimeState();
      if (notifyPlayers && weatherWasActive) {
         this.sendWeatherPayload(server, false, TellusRealtimeState.PrecipitationMode.CLEAR, false, SnowGrid.empty());
      }
   }

   private void resetRuntimeState() {
      this.requestInFlight.set(false);
      this.pendingInitialSnowPass.set(false);
      this.lastWeatherUpdateMs = 0L;
      this.lastWeatherAttemptMs = 0L;
      this.lastTimeZoneUpdateMs = 0L;
      this.lastTimeApplyTick = 0L;
      this.lastSnowQueueTick = 0L;
      this.timeZoneReady = false;
      this.lastAnchor = null;
      this.lastWeatherAttemptAnchor = null;
      this.lastTimeZoneAnchor = null;
      this.utcOffsetSeconds = 0;
      this.lastWeatherSnapshot = null;
      this.timeZone = null;
      this.timeZoneId = null;
      this.clearSnowQueue();
      TellusRealtimeState.reset();
   }

   private boolean hasWeatherStateForClients() {
      return this.hasRealtimeWeatherState() || !TellusRealtimeState.temperatureGrid().isEmpty();
   }

   private boolean hasRealtimeWeatherState() {
      return TellusRealtimeState.isWeatherEnabled()
         || TellusRealtimeState.isHistoricalSnowEnabled()
         || TellusRealtimeState.precipitationMode() != TellusRealtimeState.PrecipitationMode.CLEAR;
   }

   private static int computeGridSpacing(double worldScale) {
      double targetMeters = 32000.0;
      double spacing = targetMeters / Math.max(1.0, worldScale);
      int spacingBlocks = Mth.floor(spacing);
      return Mth.clamp(spacingBlocks, 1024, 16384);
   }

   private static int approximateUtcOffsetSeconds(EarthChunkGenerator generator, BlockPos pos) {
      double longitude = Mth.clamp(generator.longitudeFromBlock(pos.getX()), -180.0, 180.0);
      double hours = longitude / 15.0;
      return (int)Math.round(hours * 3600.0);
   }

   private record GridAnchor(int centerX, int centerZ, int spacingBlocks) {
      private static TellusRealtimeManager.GridAnchor resolve(MinecraftServer server, int baseSpacingBlocks) {
         int minX = Integer.MAX_VALUE;
         int maxX = Integer.MIN_VALUE;
         int minZ = Integer.MAX_VALUE;
         int maxZ = Integer.MIN_VALUE;
         boolean found = false;
         for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level().dimension() == Level.OVERWORLD) {
               BlockPos pos = player.blockPosition();
               minX = Math.min(minX, pos.getX());
               maxX = Math.max(maxX, pos.getX());
               minZ = Math.min(minZ, pos.getZ());
               maxZ = Math.max(maxZ, pos.getZ());
               found = true;
            }
         }
         if (!found) {
            return null;
         }

         int midpointX = (int)(((long)minX + maxX) / 2L);
         int midpointZ = (int)(((long)minZ + maxZ) / 2L);
         int centerX = Math.floorDiv(midpointX, baseSpacingBlocks) * baseSpacingBlocks;
         int centerZ = Math.floorDiv(midpointZ, baseSpacingBlocks) * baseSpacingBlocks;
         long required = Math.max(
            Math.max(Math.abs((long)minX - centerX), Math.abs((long)maxX - centerX)),
            Math.max(Math.abs((long)minZ - centerZ), Math.abs((long)maxZ - centerZ))
         );
         long spacingMultiplier = Math.max(1L, (required + baseSpacingBlocks - 1L) / baseSpacingBlocks);
         int spacingBlocks = (int)Math.min(60000000L, spacingMultiplier * baseSpacingBlocks);
         return new TellusRealtimeManager.GridAnchor(centerX, centerZ, spacingBlocks);
      }
   }

   public record WeatherSnapshot(double latitude, double longitude, int utcOffsetSeconds, String timeZoneId, float temperatureC, long updatedAtMs) {
   }

   public record WeatherReport(double latitude, double longitude, float temperatureC, String timeZoneId, String locationName) {
   }

   public enum WeatherReportRequestResult {
      QUEUED,
      RATE_LIMITED,
      UNAVAILABLE
   }
}
