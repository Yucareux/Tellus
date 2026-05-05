package com.yucareux.tellus.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TellusDiagnostics {
   private static final Logger LOGGER = LoggerFactory.getLogger("tellus");
   private static final boolean ENABLED = booleanProperty("tellus.diagnostics.enabled", true);
   private static final boolean TRAFFIC_ENABLED = booleanProperty("tellus.diagnostics.traffic", true);
   private static final boolean WORLDGEN_ENABLED = booleanProperty("tellus.diagnostics.worldgen", true);
   private static final long MAX_BYTES = longProperty("tellus.diagnostics.maxBytes", 10L * 1024L * 1024L, 1024L, 512L * 1024L * 1024L);
   private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

   private TellusDiagnostics() {
   }

   public static void traffic(String message) {
      if (ENABLED && TRAFFIC_ENABLED) {
         append("traffic.log", message);
      }
   }

   public static void traffic(String format, Object... args) {
      if (ENABLED && TRAFFIC_ENABLED) {
         traffic(String.format(Locale.ROOT, format, args));
      }
   }

   public static void worldgen(String message) {
      if (ENABLED && WORLDGEN_ENABLED) {
         append("worldgen.log", message);
      }
   }

   public static void worldgen(String format, Object... args) {
      if (ENABLED && WORLDGEN_ENABLED) {
         worldgen(String.format(Locale.ROOT, format, args));
      }
   }

   private static synchronized void append(String fileName, String message) {
      Path path = logRoot().resolve(fileName);
      try {
         Files.createDirectories(path.getParent());
         rotateIfNeeded(path);
         String line = TIMESTAMP_FORMAT.format(OffsetDateTime.now()) + " " + sanitize(message) + System.lineSeparator();
         Files.writeString(path, line, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
      } catch (IOException | RuntimeException error) {
         LOGGER.debug("Failed to write Tellus diagnostics log {}", path, error);
      }
   }

   private static void rotateIfNeeded(Path path) throws IOException {
      if (Files.isRegularFile(path) && Files.size(path) >= MAX_BYTES) {
         Path rotated = path.resolveSibling(path.getFileName() + ".1");
         Files.deleteIfExists(rotated);
         Files.move(path, rotated, StandardCopyOption.REPLACE_EXISTING);
      }
   }

   private static Path logRoot() {
      try {
         return FabricLoader.getInstance().getGameDir().resolve("tellus/logs");
      } catch (RuntimeException error) {
         return Path.of("tellus/logs");
      }
   }

   private static String sanitize(String message) {
      return message == null ? "" : message.replace('\n', ' ').replace('\r', ' ');
   }

   private static boolean booleanProperty(String key, boolean defaultValue) {
      String value = System.getProperty(key);
      return value == null ? defaultValue : Boolean.parseBoolean(value);
   }

   private static long longProperty(String key, long defaultValue, long minInclusive, long maxInclusive) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      }
      try {
         long parsed = Long.parseLong(value.trim());
         return Math.max(minInclusive, Math.min(maxInclusive, parsed));
      } catch (NumberFormatException error) {
         LOGGER.debug("Invalid long system property {}='{}', using {}", key, value, defaultValue);
         return defaultValue;
      }
   }
}
