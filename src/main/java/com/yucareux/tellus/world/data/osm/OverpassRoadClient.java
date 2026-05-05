package com.yucareux.tellus.world.data.osm;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.util.TellusDiagnostics;
import com.yucareux.tellus.world.data.source.DownloadProgressReporter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

public final class OverpassRoadClient {
   private static final String DEFAULT_ENDPOINT = "https://overpass-api.de/api/interpreter";
   private static final String DEFAULT_ENDPOINTS = String.join(
      ",", "https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter", "https://overpass.openstreetmap.ru/api/interpreter"
   );
   private static final String USER_AGENT = "Tellus/1.0 (Minecraft Mod)";
   private static final int CONNECT_TIMEOUT_MS = intProperty("tellus.osm.overpass.connectTimeoutMs", 7000, 1, 120000);
   private static final int READ_TIMEOUT_MS = intProperty("tellus.osm.overpass.readTimeoutMs", 20000, 1, 180000);
   private static final int QUERY_TIMEOUT_SECONDS = intProperty("tellus.osm.overpass.queryTimeoutSec", 25, 5, 300);
   private static final int MAX_RETRIES = intProperty("tellus.osm.overpass.maxRetries", 3, 0, 12);
   private static final long RETRY_BACKOFF_MS = longProperty("tellus.osm.overpass.retryBackoffMs", 750L, 0L, 60000L);
   private static final long RETRY_BACKOFF_JITTER_MS = longProperty("tellus.osm.overpass.retryBackoffJitterMs", 250L, 0L, 10000L);
   private static final long MIN_REQUEST_SPACING_MS = longProperty("tellus.osm.overpass.minSpacingMs", 350L, 0L, 60000L);
   private static final long RETRYABLE_STATUS_COOLDOWN_MS = longProperty("tellus.osm.overpass.retryableCooldownMs", 4000L, 0L, 300000L);
   private final URI[] endpoints;
   private final int connectTimeoutMs;
   private final int readTimeoutMs;
   private final Semaphore requestGuard = new Semaphore(1, true);
   private final AtomicLong nextAllowedRequestMs = new AtomicLong(0L);
   private final AtomicLong endpointCursor = new AtomicLong(0L);

   public OverpassRoadClient() {
      String endpointsProperty = System.getProperty("tellus.osm.overpass.endpoints");
      String singleEndpointProperty = System.getProperty("tellus.osm.overpass.endpoint");
      String endpointConfig;
      if (endpointsProperty != null && !endpointsProperty.isBlank()) {
         endpointConfig = endpointsProperty;
      } else if (singleEndpointProperty != null && !singleEndpointProperty.isBlank()) {
         endpointConfig = singleEndpointProperty;
      } else {
         endpointConfig = DEFAULT_ENDPOINTS;
      }

      this.endpoints = parseEndpoints(endpointConfig);
      this.connectTimeoutMs = CONNECT_TIMEOUT_MS;
      this.readTimeoutMs = READ_TIMEOUT_MS;
   }

   public byte[] fetchRoadTile(int zoom, int tileX, int tileY, double south, double west, double north, double east) throws IOException {
      String query = String.format(
         Locale.ROOT, "[out:json][timeout:%d];way[\"highway\"](%.7f,%.7f,%.7f,%.7f);out tags geom;", QUERY_TIMEOUT_SECONDS, south, west, north, east
      );
      IOException lastError = null;
      int totalAttempts = MAX_RETRIES + 1;
      long startEndpoint = this.endpointCursor.getAndIncrement();

      for (int attempt = 0; attempt < totalAttempts; attempt++) {
         URI endpoint = this.endpoints[Math.floorMod(startEndpoint + attempt, this.endpoints.length)];

         try {
            return this.executeQuery(endpoint, query);
         } catch (OverpassRoadClient.RetryableOverpassException var20) {
            lastError = var20;
            if (attempt >= totalAttempts - 1) {
               break;
            }

            sleepBackoff(attempt + 1L);
         } catch (IOException var21) {
            lastError = var21;
            if (attempt >= totalAttempts - 1 || isNonRetryableHttp4xx(var21)) {
               break;
            }

            sleepBackoff(attempt + 1L);
         }
      }

      throw new IOException(
         String.format(Locale.ROOT, "Overpass request failed for tile z=%d x=%d y=%d (endpoints=%d)", zoom, tileX, tileY, this.endpoints.length), lastError
      );
   }

   private byte[] executeQuery(URI endpoint, String query) throws IOException {
      this.acquireRequestGuard();

      byte[] responseBody;
      long startMs = System.currentTimeMillis();
      try {
         this.applyRateLimitDelay();
         HttpURLConnection connection = (HttpURLConnection)endpoint.toURL().openConnection();
         try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(this.connectTimeoutMs);
            connection.setReadTimeout(this.readTimeoutMs);
            connection.setDoOutput(true);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Content-Type", "text/plain; charset=UTF-8");
            byte[] payload = query.getBytes(StandardCharsets.UTF_8);

            try (OutputStream output = connection.getOutputStream()) {
               output.write(payload);
            }

            int status = connection.getResponseCode();
            if (status != 200) {
               String message = readErrorSnippet(connection);
               boolean retryableStatus = status == 408 || status == 429 || status >= 500;
               String detail = "Overpass HTTP " + status + " (" + endpoint.getHost() + ")" + (message.isEmpty() ? "" : ": " + message);
               TellusDiagnostics.traffic(
                  "Legacy Overpass roads failed endpoint=%s status=%d retryable=%s payloadBytes=%d elapsedMs=%d detail=%s",
                  endpoint,
                  status,
                  retryableStatus,
                  payload.length,
                  System.currentTimeMillis() - startMs,
                  detail
               );
               if (retryableStatus) {
                  this.applyRetryableCooldown(status, parseRetryAfterMillis(connection.getHeaderField("Retry-After")));
                  throw new OverpassRoadClient.RetryableOverpassException(detail);
               }

               throw new IOException(detail);
            }

            DownloadProgressReporter.requestStarted(connection.getContentLengthLong());

            try (InputStream input = Objects.requireNonNull(connection.getInputStream(), "overpassRoadResponse")) {
               responseBody = DownloadProgressReporter.readAllBytesWithProgress(input);
            } finally {
               DownloadProgressReporter.requestFinished();
            }
            TellusDiagnostics.traffic(
               "Legacy Overpass roads ok endpoint=%s status=%d payloadBytes=%d bytes=%d elapsedMs=%d",
               endpoint,
               status,
               payload.length,
               responseBody.length,
               System.currentTimeMillis() - startMs
            );
         } finally {
            connection.disconnect();
         }
      } finally {
         this.nextAllowedRequestMs.accumulateAndGet(System.currentTimeMillis() + MIN_REQUEST_SPACING_MS, Math::max);
         this.requestGuard.release();
      }

      return responseBody;
   }

   private void acquireRequestGuard() throws IOException {
      try {
         this.requestGuard.acquire();
      } catch (InterruptedException var2) {
         Thread.currentThread().interrupt();
         throw new IOException("Interrupted while waiting for Overpass request slot", var2);
      }
   }

   private void applyRateLimitDelay() throws IOException {
      long now = System.currentTimeMillis();
      long next = this.nextAllowedRequestMs.get();
      long waitMs = next - now;
      if (waitMs > 0L) {
         try {
            Thread.sleep(waitMs);
         } catch (InterruptedException var8) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while rate-limiting Overpass requests", var8);
         }
      }
   }

   private static void sleepBackoff(long attempt) {
      long baseDelay = RETRY_BACKOFF_MS * attempt;
      long jitter = RETRY_BACKOFF_JITTER_MS > 0L ? ThreadLocalRandom.current().nextLong(RETRY_BACKOFF_JITTER_MS + 1L) : 0L;
      long delayMs = baseDelay + jitter;

      try {
         Thread.sleep(delayMs);
      } catch (InterruptedException var9) {
         Thread.currentThread().interrupt();
      }
   }

   private void applyRetryableCooldown(int status, long retryAfterMs) {
      long statusCooldown = status == 429 ? Math.max(RETRYABLE_STATUS_COOLDOWN_MS, 10000L) : RETRYABLE_STATUS_COOLDOWN_MS;
      long cooldownMs = Math.max(statusCooldown, retryAfterMs);
      if (cooldownMs > 0L) {
         long retryAt = System.currentTimeMillis() + cooldownMs;
         this.nextAllowedRequestMs.accumulateAndGet(retryAt, Math::max);
      }
   }

   private static long parseRetryAfterMillis(String value) {
      if (value != null && !value.isBlank()) {
         String normalized = value.trim();

         try {
            long seconds = Long.parseLong(normalized);
            return Math.max(0L, seconds * 1000L);
         } catch (NumberFormatException var4) {
            return 0L;
         }
      } else {
         return 0L;
      }
   }

   private static boolean isNonRetryableHttp4xx(IOException error) {
      String message = error.getMessage();
      return message == null
         ? false
         : message.startsWith("Overpass HTTP 4") && !message.startsWith("Overpass HTTP 408") && !message.startsWith("Overpass HTTP 429");
   }

   private static String readErrorSnippet(HttpURLConnection connection) {
      try {
         String var5;
         try (InputStream error = connection.getErrorStream()) {
            if (error == null) {
               return "";
            }

            byte[] bytes = error.readNBytes(256);
            if (bytes.length == 0) {
               return "";
            }

            String raw = new String(bytes, StandardCharsets.UTF_8);
            String compact = raw.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
            compact = compact.replaceAll("\\s+", " ").trim();
            if (compact.length() > 180) {
               return compact.substring(0, 180) + "...";
            }

            var5 = compact;
         }

         return var5;
      } catch (IOException var8) {
         return "";
      }
   }

   private static URI[] parseEndpoints(String config) {
      String[] parts = Objects.requireNonNull(config, "overpassEndpointConfig").split(",");
      ArrayList<URI> parsed = new ArrayList<>(parts.length);

      for (String part : parts) {
         String trimmed = part == null ? "" : part.trim();
         if (!trimmed.isEmpty()) {
            try {
               parsed.add(URI.create(trimmed));
            } catch (IllegalArgumentException var9) {
               Tellus.LOGGER.warn("Ignoring invalid Overpass endpoint '{}'", trimmed);
            }
         }
      }

      return parsed.isEmpty() ? new URI[]{URI.create(DEFAULT_ENDPOINT)} : parsed.toArray(URI[]::new);
   }

   private static int intProperty(String key, int defaultValue, int minInclusive, int maxInclusive) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      } else {
         try {
            int parsed = Integer.parseInt(value);
            return Math.max(minInclusive, Math.min(maxInclusive, parsed));
         } catch (NumberFormatException var6) {
            Tellus.LOGGER.debug("Invalid integer system property {}='{}', using {}", new Object[]{key, value, defaultValue});
            return defaultValue;
         }
      }
   }

   private static long longProperty(String key, long defaultValue, long minInclusive, long maxInclusive) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      } else {
         try {
            long parsed = Long.parseLong(value);
            return Math.max(minInclusive, Math.min(maxInclusive, parsed));
         } catch (NumberFormatException var10) {
            Tellus.LOGGER.debug("Invalid long system property {}='{}', using {}", new Object[]{key, value, defaultValue});
            return defaultValue;
         }
      }
   }

   private static final class RetryableOverpassException extends IOException {
      private RetryableOverpassException(String message) {
         super(message);
      }
   }
}
